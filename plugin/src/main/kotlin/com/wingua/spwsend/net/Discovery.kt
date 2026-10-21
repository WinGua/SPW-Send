package com.wingua.spwsend.net

import com.wingua.spwsend.core.Constants
import com.wingua.spwsend.core.Json
import com.wingua.spwsend.core.asMap
import com.wingua.spwsend.core.intValue
import com.wingua.spwsend.core.str
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketAddress
import java.util.concurrent.ConcurrentHashMap

/** 局域网中发现的安卓接收端。 */
class Peer(
    val id: String,
    @Volatile var name: String,
    @Volatile var address: String,
    @Volatile var port: Int,
    @Volatile var appVersion: String,
    @Volatile var session: String,
    @Volatile var lastSeen: Long
) {
    @Volatile
    var connected: Boolean = false
}

/**
 * UDP 局域网发现（协议 v2）。
 *
 * - 每 2 秒向广播地址发送 hello
 * - 接收 `role=android` 且 `proto=2` 的 hello
 * - 12 秒未更新的设备视为离线
 */
class Discovery(
    private val deviceId: String,
    private val deviceName: String,
    private val session: String,
    private val portProvider: () -> Int,
    private val onChanged: () -> Unit,
    private val onPeerSessionChanged: (String) -> Unit = {}
) {

    private val peers = ConcurrentHashMap<String, Peer>()

    @Volatile
    private var socket: DatagramSocket? = null

    @Volatile
    private var running = false

    fun start() {
        if (running) return
        val s = DatagramSocket(null as SocketAddress?)
        try {
            s.reuseAddress = true
            s.broadcast = true
            s.bind(InetSocketAddress(Constants.DISCOVERY_PORT))
        } catch (e: Exception) {
            s.close()
            throw e
        }
        socket = s
        running = true

        Thread({ receiveLoop(s) }, "spw-send-discovery-rx").apply {
            isDaemon = true
            start()
        }
        Thread({ announceLoop(s) }, "spw-send-discovery-tx").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running = false
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
        peers.clear()
        onChanged()
    }

    fun peers(): List<Peer> {
        prune()
        return peers.values.sortedBy { it.name.lowercase() }
    }

    fun peer(id: String): Peer? = peers[id]

    fun markConnected(id: String) {
        peers[id]?.let {
            it.connected = true
            onChanged()
        }
    }

    fun markDisconnected(id: String) {
        peers[id]?.let {
            it.connected = false
            onChanged()
        }
    }

    fun remove(id: String) {
        if (peers.remove(id) != null) onChanged()
    }

    private fun prune() {
        val now = System.currentTimeMillis()
        var changed = false
        for ((id, peer) in peers.entries) {
            if (now - peer.lastSeen > Constants.PEER_TIMEOUT_MS && peers.remove(id) != null) {
                changed = true
            }
        }
        if (changed) onChanged()
    }

    private fun receiveLoop(s: DatagramSocket) {
        val buffer = ByteArray(8192)
        // 重启后旧线程绑定的是已关闭的 socket，必须退出而不是空转。
        while (running && socket === s) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                s.receive(packet)
            } catch (_: Exception) {
                if (!running) break else continue
            }
            try {
                handlePacket(packet)
            } catch (_: Exception) {
                // 忽略非法报文。
            }
        }
    }

    private fun handlePacket(packet: DatagramPacket) {
        val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
        val data = Json.parse(text).asMap()
        if (data.str("t") != "hello") return
        if (data.intValue("proto") != Constants.PROTO) return
        if (data.str("role") != "android") return

        val id = data.str("id")
        if (id.isEmpty() || id == deviceId) return
        val name = data.str("name").ifEmpty { "安卓设备" }
        val address = packet.address.hostAddress ?: return
        val port = data.intValue("port").let { if (it in 1..65535) it else Constants.DEFAULT_HTTP_PORT }
        val appVersion = data.str("appVersion")
        val peerSession = data.str("session")
        val now = System.currentTimeMillis()

        val existing = peers[id]
        if (existing == null) {
            peers[id] = Peer(id, name, address, port, appVersion, peerSession, now)
            onChanged()
        } else {
            val sessionChanged = peerSession.isNotEmpty() && existing.session != peerSession
            existing.name = name
            existing.address = address
            existing.port = port
            existing.appVersion = appVersion
            existing.lastSeen = now
            if (sessionChanged) {
                existing.session = peerSession
                // 对端重启后旧 token 失效。
                existing.connected = false
                onPeerSessionChanged(id)
                onChanged()
            }
        }
    }

    private fun announceLoop(s: DatagramSocket) {
        var tick = 0
        while (running && socket === s) {
            try {
                announce(s, refreshTargets = tick % 15 == 0)
            } catch (_: Exception) {
            }
            tick++
            try {
                Thread.sleep(Constants.ANNOUNCE_INTERVAL_MS)
            } catch (_: InterruptedException) {
                break
            }
        }
    }

    private var cachedTargets: List<String> = listOf("255.255.255.255")

    private fun announce(s: DatagramSocket, refreshTargets: Boolean) {
        if (refreshTargets) cachedTargets = collectBroadcastAddresses()
        val payload = Json.stringify(
            Json.obj(
                "t" to "hello",
                "proto" to Constants.PROTO,
                "id" to deviceId,
                "name" to deviceName,
                "role" to "pc",
                "form" to "desktop",
                "app" to Constants.APP_ID,
                "appVersion" to Constants.APP_VERSION,
                "port" to portProvider(),
                "session" to session
            )
        ).toByteArray(Charsets.UTF_8)
        for (target in cachedTargets) {
            try {
                s.send(
                    DatagramPacket(
                        payload,
                        payload.size,
                        InetAddress.getByName(target),
                        Constants.DISCOVERY_PORT
                    )
                )
            } catch (_: Exception) {
            }
        }
    }

    private fun collectBroadcastAddresses(): List<String> {
        val result = LinkedHashSet<String>()
        result.add("255.255.255.255")
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val nic = interfaces.nextElement()
                if (!nic.isUp || nic.isLoopback) continue
                for (addr in nic.interfaceAddresses) {
                    val broadcast = addr.broadcast ?: continue
                    result.add(broadcast.hostAddress ?: continue)
                }
            }
        } catch (_: Exception) {
        }
        return result.toList()
    }
}
