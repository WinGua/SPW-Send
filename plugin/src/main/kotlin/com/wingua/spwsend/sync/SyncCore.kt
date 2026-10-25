package com.wingua.spwsend.sync

import com.wingua.spwsend.core.Config
import com.wingua.spwsend.core.ConfigStore
import com.wingua.spwsend.core.Constants
import com.wingua.spwsend.core.Json
import com.wingua.spwsend.core.LocalFile
import com.wingua.spwsend.core.LogStore
import com.wingua.spwsend.core.Mapping
import com.wingua.spwsend.core.PathUtil
import com.wingua.spwsend.core.SyncFailure
import com.wingua.spwsend.core.SyncLog
import com.wingua.spwsend.core.asList
import com.wingua.spwsend.core.asMap
import com.wingua.spwsend.core.intValue
import com.wingua.spwsend.core.longValue
import com.wingua.spwsend.core.str
import com.wingua.spwsend.net.Discovery
import com.wingua.spwsend.net.HttpClientLite
import com.wingua.spwsend.net.HttpServerLite
import com.wingua.spwsend.net.Peer
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** 对比结果：待传新增文件（按相对路径排序）。 */
data class CompareResult(
    val mapping: Mapping,
    val files: List<LocalFile>,
    val totalBytes: Long
)

/** 同步结果。 */
data class SyncOutcome(
    val log: SyncLog,
    val cancelled: Boolean
)

/** 同步已被用户取消。 */
class CancelledException : Exception("已取消")

/** 对端返回的业务错误。 */
class RemoteException(val code: String, message: String) : Exception(message)

/**
 * 同步核心：发现、配对、索引对比与推送。
 *
 * 同步方向固定为 PC → Android；只按相对路径判断「新增」，不比对内容、不删除。
 * 每台设备同一时间只允许一个同步任务。
 */
class SyncCore(
    private val configStore: ConfigStore,
    private val logStore: LogStore
) {

    @Volatile
    var config: Config = configStore.load()
        private set

    val session: String = randomHex(16)

    @Volatile
    var onChanged: (() -> Unit)? = null

    private var server: HttpServerLite? = null
    private var discovery: Discovery? = null

    /** 对端设备 id -> Android 下发的 token。 */
    private val peerTokens = ConcurrentHashMap<String, String>()

    /** 由 Android 主动配对建立连接的设备 id（此时本机还没有可用于调用对方的 token）。 */
    private val inboundPeers = ConcurrentHashMap.newKeySet<String>()

    private val endpoints = PcEndpoints(
        session = session,
        configProvider = { config },
        busyProvider = { busy },
        onPeerDisconnected = { id ->
            peerTokens.remove(id)
            inboundPeers.remove(id)
            discovery?.markDisconnected(id)
            notifyChanged()
        },
        onPeerPaired = { id, androidToken ->
            if (!androidToken.isNullOrEmpty()) peerTokens[id] = androidToken
            inboundPeers.add(id)
            discovery?.markConnected(id)
            notifyChanged()
        },
        onRemoteLog = { data ->
            // MySend 安卓端主动拉取后推送日志：{name, count, bytes, files}。
            // 本机同样留存一份，字段换算成本地日志结构。
            runCatching {
                val name = data.str("name")
                val count = data.intValue("count")
                if (name.isNotEmpty() && count > 0) {
                    val now = System.currentTimeMillis()
                    logStore.add(
                        SyncLog(
                            task = name,
                            startedAt = now,
                            finishedAt = now,
                            total = count,
                            success = count,
                            failed = 0,
                            bytes = data.longValue("bytes")
                        )
                    )
                    notifyChanged()
                }
            }
        }
    )

    @Volatile
    var actualPort: Int = 0
        private set

    @Volatile
    var busy: Boolean = false
        private set

    /** 最近一次服务启动错误（用于面板提示）。 */
    @Volatile
    var startupError: String? = null
        private set

    val isRunning: Boolean
        get() = server?.isRunning == true

    fun start() {
        if (server != null) return
        startupError = null
        busy = false

        val http = HttpServerLite { request -> endpoints.handle(request) }
        try {
            actualPort = http.start(config.port)
            server = http
        } catch (e: Exception) {
            actualPort = 0
            startupError = "接收服务启动失败：${e.message}"
            return
        }

        val disc = Discovery(
            deviceId = config.deviceId,
            deviceNameProvider = { config.deviceName },
            session = session,
            portProvider = { actualPort },
            onChanged = { notifyChanged() },
            onPeerSessionChanged = { id ->
                peerTokens.remove(id)
                endpoints.forget(id)
            }
        )
        try {
            disc.start()
            discovery = disc
        } catch (e: Exception) {
            startupError = "设备发现启动失败（端口 ${Constants.DISCOVERY_PORT}）：${e.message}"
        }
    }

    fun stop() {
        val disc = discovery
        val tokens = peerTokens.entries.map { it.key to it.value }
        if (tokens.isNotEmpty()) {
            val peers = disc?.peers().orEmpty().associateBy { it.id }
            Thread({
                for ((id, token) in tokens) {
                    val peer = peers[id] ?: continue
                    runCatching {
                        HttpClientLite.post(
                            host = peer.address,
                            port = peer.port,
                            path = "/v2/disconnect",
                            headers = authHeaders(token) + mapOf(
                                "Content-Type" to "application/json; charset=utf-8"
                            ),
                            body = Json.stringify(Json.obj("reason" to "user"))
                                .toByteArray(Charsets.UTF_8),
                            readTimeoutMs = 1_500
                        )
                    }
                }
            }, "spw-send-disconnect").apply { isDaemon = true }.start()
        }
        disc?.stop()
        discovery = null
        server?.stop()
        server = null
        actualPort = 0
        peerTokens.clear()
        inboundPeers.clear()
        endpoints.clearTokens()
        busy = false
        notifyChanged()
    }

    /** 整体替换映射列表，避免 UI 线程与 HTTP 服务线程并发修改同一集合。 */
    @Synchronized
    fun updateMappings(mappings: List<Mapping>) {
        config = config.copy(mappings = mappings)
        configStore.save(config)
        notifyChanged()
    }

    /** 更新本机名称；发现广播与配对请求都读取该配置，下一次广播即生效。 */
    @Synchronized
    fun updateDeviceName(name: String) {
        config = config.copy(deviceName = name)
        configStore.save(config)
        notifyChanged()
    }

    fun peers(): List<Peer> = discovery?.peers() ?: emptyList()

    fun isConnected(peerId: String): Boolean =
        peerTokens.containsKey(peerId) || inboundPeers.contains(peerId)

    fun connect(peer: Peer): Boolean {
        if (peerTokens.containsKey(peer.id)) {
            discovery?.markConnected(peer.id)
            return true
        }
        // 主动配对时附带本机签发的 token，让 MySend 安卓端配对后可直接反向调用本机。
        val ownToken = endpoints.issueTokenFor(peer.id)
        val body = Json.stringify(
            Json.obj(
                "id" to config.deviceId,
                "name" to config.deviceName,
                "role" to "pc",
                "form" to "desktop",
                "app" to Constants.APP_ID,
                "appVersion" to Constants.APP_VERSION,
                "proto" to Constants.PROTO,
                "port" to actualPort,
                "session" to session,
                "token" to ownToken
            )
        ).toByteArray(Charsets.UTF_8)
        val result = HttpClientLite.post(
            host = peer.address,
            port = peer.port,
            path = "/v2/pair",
            headers = mapOf(
                Constants.HEADER_PROTO to "${Constants.PROTO}",
                "Content-Type" to "application/json; charset=utf-8"
            ),
            body = body,
            readTimeoutMs = Constants.CONTROL_TIMEOUT_MS
        )
        if (result.status != 200) return false
        val token = result.json().str("token")
        if (token.isEmpty()) return false
        peerTokens[peer.id] = token
        discovery?.markConnected(peer.id)
        notifyChanged()
        return true
    }

    fun disconnect(peer: Peer) {
        val token = peerTokens.remove(peer.id)
        if (token != null) {
            runCatching {
                HttpClientLite.post(
                    host = peer.address,
                    port = peer.port,
                    path = "/v2/disconnect",
                    headers = authHeaders(token) + mapOf(
                        "Content-Type" to "application/json; charset=utf-8"
                    ),
                    body = Json.stringify(Json.obj("reason" to "user"))
                        .toByteArray(Charsets.UTF_8),
                    readTimeoutMs = 5_000
                )
            }
        }
        endpoints.forget(peer.id)
        inboundPeers.remove(peer.id)
        discovery?.markDisconnected(peer.id)
        notifyChanged()
    }

    /** 对比源目录与目标索引，返回待传的新增文件。 */
    fun compare(peer: Peer, mapping: Mapping): CompareResult {
        val token = requireToken(peer)
        val sourceRoot = File(mapping.sourcePath)
        if (!sourceRoot.isDirectory) {
            throw IOException("源目录不存在：${mapping.sourcePath}")
        }
        val targetPaths = fetchTargetIndex(peer, token, mapping.name)
        val source = PathUtil.scan(sourceRoot)
        val pending = source.filter { !targetPaths.contains(it.relativePath) }
        return CompareResult(
            mapping = mapping,
            files = pending,
            totalBytes = pending.sumOf { it.size }
        )
    }

    /**
     * 推送 [pending] 到对端。
     *
     * @param cancelled 每个分片前都会检查，返回 true 立即停止。
     */
    fun sync(
        peer: Peer,
        pending: CompareResult,
        onProgress: (processed: Int, total: Int, path: String) -> Unit,
        cancelled: () -> Boolean
    ): SyncOutcome {
        if (busy) {
            throw IllegalStateException("已有同步任务进行中")
        }
        val token = requireToken(peer)
        busy = true
        val startedAt = System.currentTimeMillis()
        val files = pending.files
        val total = files.size
        var processed = 0
        var success = 0
        var bytes = 0L
        var cancelledFlag = false
        val failures = ArrayList<SyncFailure>()
        // 与 MySend 一致：先批量查询续传偏移，失败时逐文件回退。
        val offsets = runCatching {
            queryResumeBatch(peer, token, pending.mapping.name, files)
        }.getOrDefault(emptyMap())
        val syncedNames = ArrayList<String>()

        try {
            for (file in files) {
                if (cancelled()) {
                    cancelledFlag = true
                    break
                }
                try {
                    val rawOffset = offsets[file.relativePath]
                        ?: queryResume(peer, token, pending.mapping.name, file)
                    // 批量查询不校验上限：分片恰好等于文件大小时按 0 重传，
                    // 否则会跳过上传，导致分片永远无法原子落盘。
                    val offset = if (rawOffset in 0 until file.size) rawOffset else 0L
                    if (file.size == 0L || offset < file.size) {
                        try {
                            upload(peer, token, pending.mapping, file, offset, cancelled)
                        } catch (e: RemoteException) {
                            // 分片可能在批量查询后被清理：与 MySend 一样重查一次偏移后重试。
                            if (e.code != "RANGE_INVALID") throw e
                            val retryOffset = if (offset > 0) {
                                0L
                            } else {
                                queryResume(peer, token, pending.mapping.name, file)
                            }
                            upload(peer, token, pending.mapping, file, retryOffset, cancelled)
                        }
                    }
                    success++
                    bytes += file.size
                    syncedNames.add(file.file.name)
                } catch (_: CancelledException) {
                    cancelledFlag = true
                    break
                } catch (e: Exception) {
                    failures.add(SyncFailure(file.relativePath, reasonOf(e)))
                }
                processed++
                onProgress(processed, total, file.relativePath)
            }
        } finally {
            busy = false
            notifyChanged()
        }

        val log = SyncLog(
            task = pending.mapping.name,
            startedAt = startedAt,
            finishedAt = System.currentTimeMillis(),
            total = total,
            success = success,
            failed = failures.size,
            bytes = bytes,
            failures = failures
        )
        // 与 MySend 一致：只有实际同步成功的文件才记录并推送日志。
        if (syncedNames.isNotEmpty()) {
            runCatching { logStore.add(log) }
            runCatching {
                HttpClientLite.post(
                    host = peer.address,
                    port = peer.port,
                    path = "/v2/log",
                    headers = authHeaders(token) + mapOf(
                        "Content-Type" to "application/json; charset=utf-8"
                    ),
                    body = Json.stringify(
                        Json.obj(
                            "name" to pending.mapping.name,
                            "count" to syncedNames.size,
                            "bytes" to bytes,
                            "files" to syncedNames
                        )
                    ).toByteArray(Charsets.UTF_8),
                    readTimeoutMs = 5_000
                )
            }
        }
        return SyncOutcome(log, cancelledFlag)
    }

    private fun fetchTargetIndex(peer: Peer, token: String, mappingName: String): Set<String> {
        val result = HttpClientLite.get(
            host = peer.address,
            port = peer.port,
            path = "/v2/index",
            query = mapOf("name" to mappingName),
            headers = authHeaders(token),
            readTimeoutMs = Constants.INDEX_TIMEOUT_MS
        )
        if (result.status != 200) throw RemoteException(codeOf(result), messageOf(result))
        return result.json()["files"].asList()
            .mapNotNull { item ->
                val path = item.asMap().str("p")
                if (path.isEmpty()) null else path
            }
            .toHashSet()
    }

    private fun queryResume(peer: Peer, token: String, mappingName: String, file: LocalFile): Long {
        val result = HttpClientLite.get(
            host = peer.address,
            port = peer.port,
            path = "/v2/resume",
            query = mapOf(
                "name" to mappingName,
                "path" to file.relativePath,
                "size" to file.size.toString(),
                "mtime" to file.mtimeMs.toString()
            ),
            headers = authHeaders(token),
            readTimeoutMs = Constants.CONTROL_TIMEOUT_MS
        )
        if (result.status != 200) throw RemoteException(codeOf(result), messageOf(result))
        val offset = result.json().longValue("offset")
        return if (offset in 0 until file.size) offset else 0L
    }

    /**
     * 批量查询续传偏移（MySend `/v2/resume/batch`）。
     *
     * 对端未实现或请求失败时返回空表，调用方会逐文件回退到 `/v2/resume`。
     */
    private fun queryResumeBatch(
        peer: Peer,
        token: String,
        mappingName: String,
        files: List<LocalFile>
    ): Map<String, Long> {
        if (files.isEmpty()) return emptyMap()
        val merged = HashMap<String, Long>()
        val chunkSize = 2000
        var start = 0
        while (start < files.size) {
            val end = minOf(start + chunkSize, files.size)
            val items = files.subList(start, end).map {
                Json.obj("p" to it.relativePath, "s" to it.size, "m" to it.mtimeMs)
            }
            val result = HttpClientLite.post(
                host = peer.address,
                port = peer.port,
                path = "/v2/resume/batch",
                headers = authHeaders(token) + mapOf(
                    "Content-Type" to "application/json; charset=utf-8"
                ),
                body = Json.stringify(
                    Json.obj("name" to mappingName, "items" to items)
                ).toByteArray(Charsets.UTF_8),
                readTimeoutMs = Constants.CONTROL_TIMEOUT_MS
            )
            if (result.status != 200) return emptyMap()
            val offsets = result.json()["offsets"].asMap()
            for ((path, value) in offsets) {
                val offset = (value as? Number)?.toLong() ?: continue
                merged[path] = offset
            }
            start = end
        }
        return merged
    }

    private fun upload(
        peer: Peer,
        token: String,
        mapping: Mapping,
        file: LocalFile,
        offset: Long,
        cancelled: () -> Boolean
    ) {
        val length = file.size - offset
        val headers = authHeaders(token) + mapOf(
            "X-Offset" to offset.toString(),
            "Content-Type" to "application/octet-stream"
        )
        val timeout = fileTimeoutMs(file.size)
        val result = HttpClientLite.postStream(
            host = peer.address,
            port = peer.port,
            path = "/v2/upload",
            query = mapOf(
                "name" to mapping.name,
                "path" to file.relativePath,
                "size" to file.size.toString(),
                "mtime" to file.mtimeMs.toString()
            ),
            headers = headers,
            length = length,
            readTimeoutMs = timeout
        ) { out ->
            RandomAccessFile(file.file, "r").use { raf ->
                raf.seek(offset)
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    if (cancelled()) throw CancelledException()
                    val read = raf.read(buffer)
                    if (read <= 0) break
                    out.write(buffer, 0, read)
                }
            }
        }
        if (result.status != 200) throw RemoteException(codeOf(result), messageOf(result))
    }

    private fun requireToken(peer: Peer): String {
        peerTokens[peer.id]?.let { return it }
        // Android 主动配对时本机还没有调用对方的 token，这里补一次配对。
        if (connect(peer)) {
            peerTokens[peer.id]?.let { return it }
        }
        throw IOException("尚未连接「${peer.name}」")
    }

    private fun authHeaders(token: String): Map<String, String> = mapOf(
        Constants.HEADER_PROTO to "${Constants.PROTO}",
        Constants.HEADER_TOKEN to token
    )

    private fun codeOf(result: HttpClientLite.Result): String {
        val json = result.json()
        val code = json.str("code")
        return if (code.isNotEmpty()) code else "HTTP_${result.status}"
    }

    private fun messageOf(result: HttpClientLite.Result): String {
        val json = result.json()
        val message = json.str("message")
        return if (message.isNotEmpty()) message else "HTTP ${result.status}"
    }

    private fun reasonOf(e: Exception): String = when (e) {
        is RemoteException -> e.code
        is SocketTimeoutException -> "TIMEOUT"
        is CancelledException -> "CANCELLED"
        is IOException -> "IO_ERROR"
        else -> e.message?.take(60) ?: "IO_ERROR"
    }

    private fun fileTimeoutMs(size: Long): Int {
        val perMiB = size / (1024 * 1024) * 2_000
        return perMiB.coerceIn(60_000L, Constants.MAX_FILE_TIMEOUT_MS.toLong()).toInt()
    }

    private fun notifyChanged() {
        onChanged?.invoke()
    }

    companion object {
        private fun randomHex(bytes: Int): String {
            val data = ByteArray(bytes)
            SecureRandom().nextBytes(data)
            return data.joinToString("") { String.format(Locale.ROOT, "%02x", it) }
        }
    }
}
