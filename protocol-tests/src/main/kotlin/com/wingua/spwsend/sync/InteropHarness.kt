package com.wingua.spwsend.sync

import com.wingua.spwsend.core.*
import com.wingua.spwsend.net.Peer
import java.io.File

/** 与真正 MySend Dart 服务互测；不加载 Salt Player 宿主，不写用户配置目录。 */
fun main(args: Array<String>) {
    val root = File(args.single()).apply { mkdirs() }
    val source = File(root, "pc").apply { mkdirs() }
    val store = ConfigStore(File(root, "config"))
    val cfg =
        store
            .load()
            .copy(
                deviceId = "pc-plugin",
                deviceName = "SPW test",
                port = 0,
                mappings = listOf(Mapping("music", "music", source.path)),
            )
    store.save(cfg)
    val core = SyncCore(store, LogStore(File(root, "logs")))
    try {
        core.config.port = 0
        core.start()
        check(core.isRunning) { core.startupError ?: "server failed" }
        println(
            Json.stringify(
                Json.obj(
                    "port" to core.actualPort,
                    "fingerprint" to core.identity.fingerprint,
                    "id" to cfg.deviceId,
                )
            )
        )
        while (true) {
            val line = readlnOrNull() ?: break
            val input = Json.parse(line).asMap()
            try {
                when (input.str("op")) {
                    "stop" -> break
                    "snapshot",
                    "outboundSnapshot" -> {
                        val state =
                            if (input.str("op") == "snapshot") core.remoteProgress.snapshot
                            else core.outboundProgress.snapshot
                        println(
                            Json.stringify(
                                Json.obj(
                                    "ok" to true,
                                    "finished" to state?.finished,
                                    "error" to state?.error,
                                    "sent" to state?.sent,
                                    "received" to state?.received,
                                    "bytes" to state?.bytes,
                                )
                            )
                        )
                    }
                    "sync" -> {
                        val peer =
                            Peer(
                                input.str("id"),
                                "MySend test",
                                "127.0.0.1",
                                input.intValue("port"),
                                "test",
                                "dart",
                                System.currentTimeMillis(),
                                input.str("fingerprint"),
                            )
                        check(core.connect(peer)) { "pair failed" }
                        val pending = core.compare(peer, cfg.mappings.single())
                        val result = core.sync(peer, pending, { _, _, _, _ -> }, { false })
                        println(
                            Json.stringify(
                                Json.obj(
                                    "ok" to true,
                                    "cancelled" to result.cancelled,
                                    "sent" to result.log.sentCount,
                                    "received" to result.log.receivedCount,
                                    "failed" to result.log.failed,
                                )
                            )
                        )
                    }
                    else -> error("unknown operation")
                }
            } catch (e: Exception) {
                println(Json.stringify(Json.obj("ok" to false, "error" to e.message)))
            }
        }
    } finally {
        core.stop()
    }
}
