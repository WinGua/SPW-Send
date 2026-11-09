package com.wingua.spwsend.sync

import com.wingua.spwsend.core.Config
import com.wingua.spwsend.core.ConfigStore
import com.wingua.spwsend.core.Constants
import com.wingua.spwsend.core.Json
import com.wingua.spwsend.core.LocalFile
import com.wingua.spwsend.core.LogStore
import com.wingua.spwsend.core.Mapping
import com.wingua.spwsend.core.PathUtil
import com.wingua.spwsend.core.RemoteFile
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
import java.util.concurrent.atomic.AtomicBoolean

/** 单个文件在本机视角下的传输方向。 */
enum class SyncDirection { Send, Receive }

/** 对比结果：待发送与待接收的新增文件，均按相对路径排序。 */
data class CompareResult(
    val mapping: Mapping,
    val toSend: List<LocalFile>,
    val toReceive: List<RemoteFile>,
    val sendBytes: Long,
    val receiveBytes: Long,
    val receiveSupported: Boolean = true
) {
    val totalBytes: Long get() = sendBytes + receiveBytes
    val isEmpty: Boolean get() = toSend.isEmpty() && toReceive.isEmpty()
}

/** 同步结果。 */
data class SyncOutcome(
    val log: SyncLog,
    val cancelled: Boolean,
    val receiveSkipped: Boolean = false
)

/** 同步已被用户取消。 */
class CancelledException : Exception("已取消")

/** 对端返回的业务错误。 */
class RemoteException(val code: String, message: String) : Exception(message)

/**
 * 同步核心：发现、配对、双向索引对比、先发后收与续传。
 *
 * - 「本机有、对端无」→ 本机发送
 * - 「对端有、本机无」→ 本机接收
 * - 两端都有 → 跳过，不比对内容、不覆盖、不删除
 * 每台设备同一时间只允许一个同步任务。
 */
class SyncCore(
    private val configStore: ConfigStore,
    private val logStore: LogStore
) {

    @Volatile
    var config: Config = configStore.load()
        private set

    /** 本次会话标识；每次启动服务（含端口切换）重新生成，通知对端旧 token 失效。 */
    @Volatile
    var session: String = randomHex(16)
        private set

    @Volatile
    var onChanged: (() -> Unit)? = null

    private var server: HttpServerLite? = null
    private var discovery: Discovery? = null

    /** 对端设备 id -> Android 下发的 token。 */
    private val peerTokens = ConcurrentHashMap<String, String>()

    /** 对端设备 id -> 设备名称，用于旧版日志缺少 peer 字段时补全。 */
    private val peerNames = ConcurrentHashMap<String, String>()

    /** 由 Android 主动配对建立连接的设备 id（此时本机还没有可用于调用对方的 token）。 */
    private val inboundPeers = ConcurrentHashMap.newKeySet<String>()

    /** 是否有对端正在向本机上传文件。 */
    private val inboundActive = AtomicBoolean(false)

    /** Windows 文件系统不区分大小写，对比时需要额外忽略大小写。 */
    private val caseInsensitiveFs =
        System.getProperty("os.name").orEmpty().lowercase(Locale.ROOT).contains("win")

    private val endpoints = PcEndpoints(
        sessionProvider = { session },
        configProvider = { config },
        busyProvider = { busy },
        onPeerDisconnected = { id ->
            peerTokens.remove(id)
            inboundPeers.remove(id)
            peerNames.remove(id)
            discovery?.markDisconnected(id)
            notifyChanged()
        },
        onPeerPaired = { id, name, androidToken ->
            if (!androidToken.isNullOrEmpty()) peerTokens[id] = androidToken
            if (name.isNotEmpty()) peerNames[id] = name
            inboundPeers.add(id)
            discovery?.markConnected(id)
            notifyChanged()
        },
        onRemoteLog = { deviceId, data -> handleRemoteLog(deviceId, data) },
        onUploadStateChanged = { active ->
            inboundActive.set(active)
            notifyChanged()
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

    /** 手机是否正在向本机推送文件。 */
    val isInboundActive: Boolean
        get() = inboundActive.get()

    fun start() {
        if (server != null) return
        startupError = null
        busy = false
        inboundActive.set(false)
        session = randomHex(16)

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

        // 两端都可能接收文件，启动时清理各自映射根下的过期分片。
        // 放到后台线程，避免网络盘或大目录阻塞面板打开。
        val mappings = config.mappings
        Thread({
            for (mapping in mappings) {
                val root = File(mapping.sourcePath)
                if (root.isDirectory) {
                    runCatching { PartStore.cleanup(root) }
                }
            }
        }, "spw-send-part-cleanup").apply { isDaemon = true }.start()
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
        peerNames.clear()
        inboundPeers.clear()
        inboundActive.set(false)
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

    /** 切换 HTTP 服务端口；立即重启服务，并生成新会话使双方旧 token 失效。 */
    @Synchronized
    fun updatePort(port: Int) {
        if (port !in 1024..65535) return
        config = config.copy(port = port)
        configStore.save(config)
        stop()
        start()
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
        peerNames[peer.id] = peer.name
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
        peerNames.remove(peer.id)
        discovery?.markDisconnected(peer.id)
        notifyChanged()
    }

    /** 同时扫描本机与对端，返回待发送与待接收的新增文件。 */
    fun compare(peer: Peer, mapping: Mapping): CompareResult {
        if (busy || inboundActive.get() || endpoints.isUploading) {
            throw IllegalStateException("手机正在同步，请稍后再试")
        }
        val token = requireToken(peer)
        val sourceRoot = File(mapping.sourcePath)
        if (!sourceRoot.isDirectory) {
            throw IOException("同步目录不存在：${mapping.sourcePath}")
        }
        val remote = fetchTargetIndex(peer, token, mapping.name)
        val source = PathUtil.scan(sourceRoot)
        val localPaths = source.map { it.relativePath }.toHashSet()
        val localPathsLower: Set<String> = if (caseInsensitiveFs) {
            source.map { it.relativePath.lowercase(Locale.ROOT) }.toHashSet()
        } else {
            emptySet()
        }
        val toSend = source.filter { !remote.containsKey(it.relativePath) }
        val toReceive = remote.values.filter { remoteFile ->
            !localPaths.contains(remoteFile.relativePath) &&
                !(caseInsensitiveFs &&
                    localPathsLower.contains(remoteFile.relativePath.lowercase(Locale.ROOT)))
        }.sortedBy { it.relativePath }
        val receiveSupported = if (toReceive.isEmpty()) {
            true
        } else {
            probeDownloadSupport(peer, token, mapping.name)
        }
        return CompareResult(
            mapping = mapping,
            toSend = toSend,
            toReceive = toReceive,
            sendBytes = toSend.sumOf { it.size },
            receiveBytes = toReceive.sumOf { it.size },
            receiveSupported = receiveSupported
        )
    }

    /**
     * 执行双向同步：先推送本机新增，再拉取对端新增。
     *
     * 单个文件失败不中断任务；取消会立即停止当前阶段并跳过后续阶段。
     */
    fun sync(
        peer: Peer,
        pending: CompareResult,
        onProgress: (processed: Int, total: Int, path: String, direction: SyncDirection) -> Unit,
        cancelled: () -> Boolean
    ): SyncOutcome {
        if (busy || inboundActive.get() || endpoints.isUploading) {
            throw IllegalStateException("已有同步任务进行中")
        }
        val token = requireToken(peer)
        busy = true
        val startedAt = System.currentTimeMillis()
        val sendFiles = pending.toSend
        val receiveFiles = if (pending.receiveSupported) pending.toReceive else emptyList()
        var receiveSkipped = pending.toReceive.isNotEmpty() && !pending.receiveSupported
        val total = sendFiles.size + receiveFiles.size
        var processed = 0
        var sentSuccess = 0
        var sentBytes = 0L
        var recvSuccess = 0
        var recvBytes = 0L
        var cancelledFlag = false
        val failures = ArrayList<SyncFailure>()
        val sentNames = ArrayList<String>()
        val receivedNames = ArrayList<String>()

        try {
            // 阶段 1：推送本机新增。
            if (sendFiles.isNotEmpty()) {
                val offsets = runCatching {
                    queryResumeBatch(peer, token, pending.mapping.name, sendFiles)
                }.getOrDefault(emptyMap())
                for (file in sendFiles) {
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
                                when (e.code) {
                                    // 对比后对端已拥有该文件：按成功跳过。
                                    "ALREADY_EXISTS" -> Unit
                                    "RANGE_INVALID" -> {
                                        val retryOffset = if (offset > 0) {
                                            0L
                                        } else {
                                            queryResume(peer, token, pending.mapping.name, file)
                                        }
                                        try {
                                            upload(
                                                peer,
                                                token,
                                                pending.mapping,
                                                file,
                                                retryOffset,
                                                cancelled
                                            )
                                        } catch (retry: RemoteException) {
                                            if (retry.code != "ALREADY_EXISTS") throw retry
                                        }
                                    }

                                    else -> throw e
                                }
                            }
                        }
                        sentSuccess++
                        sentBytes += file.size
                        sentNames.add(fileName(file.relativePath))
                    } catch (_: CancelledException) {
                        cancelledFlag = true
                        break
                    } catch (e: Exception) {
                        failures.add(SyncFailure(file.relativePath, reasonOf(e), "send"))
                    }
                    processed++
                    onProgress(processed, total, file.relativePath, SyncDirection.Send)
                }
            }

            // 阶段 2：拉取对端新增。
            if (!cancelledFlag && receiveFiles.isNotEmpty()) {
                for (file in receiveFiles) {
                    if (cancelled()) {
                        cancelledFlag = true
                        break
                    }
                    try {
                        pullOne(peer, token, pending.mapping, file, cancelled)
                        recvSuccess++
                        recvBytes += file.size
                        receivedNames.add(fileName(file.relativePath))
                    } catch (_: CancelledException) {
                        cancelledFlag = true
                        break
                    } catch (e: RemoteException) {
                        if (e.code == "NOT_FOUND") {
                            // 对端缺少 /v2/file，通常是旧版 MySend。
                            receiveSkipped = true
                            break
                        }
                        failures.add(SyncFailure(file.relativePath, e.code, "recv"))
                    } catch (e: Exception) {
                        failures.add(SyncFailure(file.relativePath, reasonOf(e), "recv"))
                    }
                    processed++
                    onProgress(processed, total, file.relativePath, SyncDirection.Receive)
                }
            }
        } finally {
            busy = false
            notifyChanged()
        }

        val log = SyncLog(
            task = pending.mapping.name,
            peerName = peer.name,
            startedAt = startedAt,
            finishedAt = System.currentTimeMillis(),
            total = total,
            success = sentSuccess + recvSuccess,
            failed = failures.size,
            bytes = sentBytes + recvBytes,
            sentCount = sentSuccess,
            sentBytes = sentBytes,
            sentFiles = sentNames,
            receivedCount = recvSuccess,
            receivedBytes = recvBytes,
            receivedFiles = receivedNames,
            failures = failures
        )
        // 只有实际成功同步了文件才记录并推送日志。
        if (sentNames.isNotEmpty() || receivedNames.isNotEmpty()) {
            runCatching { logStore.add(log) }
            postLog(peer, token, pending.mapping.name, log)
        }
        return SyncOutcome(log, cancelledFlag, receiveSkipped)
    }

    private fun fetchTargetIndex(
        peer: Peer,
        token: String,
        mappingName: String
    ): Map<String, RemoteFile> {
        val result = HttpClientLite.get(
            host = peer.address,
            port = peer.port,
            path = "/v2/index",
            query = mapOf("name" to mappingName),
            headers = authHeaders(token),
            readTimeoutMs = Constants.INDEX_TIMEOUT_MS
        )
        if (result.status != 200) throw RemoteException(codeOf(result), messageOf(result))
        val files = LinkedHashMap<String, RemoteFile>()
        for (item in result.json()["files"].asList()) {
            val map = item.asMap()
            val path = map.str("p")
            if (path.isEmpty()) continue
            files[path] = RemoteFile(
                relativePath = path,
                size = map.longValue("s"),
                mtimeMs = map.longValue("m")
            )
        }
        return files
    }

    /**
     * 探测对端是否支持 `GET /v2/file`。
     *
     * 新端对不存在文件返回 `FILE_NOT_FOUND`，旧端因没有该端点返回 `NOT_FOUND`。
     */
    private fun probeDownloadSupport(peer: Peer, token: String, mappingName: String): Boolean {
        val result = HttpClientLite.get(
            host = peer.address,
            port = peer.port,
            path = "/v2/file",
            query = mapOf(
                "name" to mappingName,
                "path" to "spw-send-probe-${randomHex(4)}"
            ),
            headers = authHeaders(token),
            readTimeoutMs = Constants.CONTROL_TIMEOUT_MS
        )
        val code = result.json().str("code")
        return when {
            result.status == 401 -> throw IOException("会话已失效，请重新连接「${peer.name}」")
            result.status == 409 -> throw IOException("对端正在同步，请稍后再试")
            result.status == 404 && code == "NOT_FOUND" -> false
            result.status == 404 && code.isEmpty() -> false
            else -> true
        }
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
        // 旧版对端缺少该端点时从 0 开始，不阻塞推送。
        if (result.status == 404) return 0L
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

    /**
     * 拉取单个文件：本地分片长度作为 Range 起点，校验字节数后原子落盘。
     */
    private fun pullOne(
        peer: Peer,
        token: String,
        mapping: Mapping,
        remote: RemoteFile,
        cancelled: () -> Boolean
    ) {
        val root = File(mapping.sourcePath)
        val relativePath = remote.relativePath
        if (!PathUtil.isValidRelative(relativePath)) {
            throw RemoteException("PATH_INVALID", "对端返回了非法路径")
        }
        val dst = PathUtil.resolveSafely(root, relativePath)
            ?: throw RemoteException("PATH_INVALID", "路径非法：$relativePath")
        // 目标已存在则不覆盖；Windows 下仅大小写不同也会被 exists() 识别。
        if (dst.exists()) return

        val part = PartStore.partFile(root, relativePath)
        val offset = PartStore.currentOffset(root, relativePath, remote.size, remote.mtimeMs)
        PartStore.writeMeta(
            root,
            relativePath,
            PartMeta(relativePath, remote.size, remote.mtimeMs)
        )

        if (offset < remote.size) {
            try {
                downloadPart(peer, token, mapping, remote, offset, part, cancelled)
            } catch (e: RemoteException) {
                if (e.code != "RANGE_INVALID" || offset == 0L) throw e
                PartStore.discard(root, relativePath)
                PartStore.writeMeta(
                    root,
                    relativePath,
                    PartMeta(relativePath, remote.size, remote.mtimeMs)
                )
                downloadPart(peer, token, mapping, remote, 0L, part, cancelled)
            }
        } else if (!part.isFile) {
            // 空文件不会触发下载，需显式创建 0 字节分片以便后续原子改名。
            part.parentFile?.mkdirs()
            part.writeBytes(ByteArray(0))
        }

        val received = part.length()
        if (received > remote.size) {
            PartStore.discard(root, relativePath)
            throw RemoteException("INCOMPLETE", "数据超出预期大小，已重置分片")
        }
        if (received != remote.size) {
            throw RemoteException("INCOMPLETE", "拉取数据不完整，已保留分片")
        }

        dst.parentFile?.mkdirs()
        if (!PartStore.promote(part, dst)) {
            // 竞争期间目标被其他任务写入：不覆盖，丢弃分片。
            PartStore.discard(root, relativePath)
            return
        }
        if (remote.mtimeMs > 0) runCatching { dst.setLastModified(remote.mtimeMs) }
        PartStore.discard(root, relativePath)
    }

    private fun downloadPart(
        peer: Peer,
        token: String,
        mapping: Mapping,
        remote: RemoteFile,
        start: Long,
        part: File,
        cancelled: () -> Boolean
    ) {
        val result = HttpClientLite.download(
            host = peer.address,
            port = peer.port,
            path = "/v2/file",
            query = mapOf(
                "name" to mapping.name,
                "path" to remote.relativePath
            ),
            headers = authHeaders(token),
            rangeStart = start,
            readTimeoutMs = fileTimeoutMs(remote.size),
            target = part,
            append = start > 0,
            checkCancelled = { if (cancelled()) throw CancelledException() }
        )
        if (result.status != 200 && result.status != 206) {
            throw RemoteException(codeOf(result), messageOf(result))
        }
    }

    private fun postLog(peer: Peer, token: String, mappingName: String, log: SyncLog) {
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
                        "name" to mappingName,
                        "peer" to config.deviceName,
                        "time" to log.finishedAt,
                        "sent" to Json.obj(
                            "c" to log.sentCount,
                            "b" to log.sentBytes,
                            "f" to log.sentFiles
                        ),
                        "recv" to Json.obj(
                            "c" to log.receivedCount,
                            "b" to log.receivedBytes,
                            "f" to log.receivedFiles
                        ),
                        // 旧版对端只认识合计字段。
                        "count" to log.success,
                        "bytes" to log.bytes,
                        "files" to (log.sentFiles + log.receivedFiles)
                    )
                ).toByteArray(Charsets.UTF_8),
                readTimeoutMs = 5_000
            )
        }
    }

    /**
     * 对端推送来的日志。
     *
     * 新报文站在对端视角，需要互换发送/接收；旧版报文没有方向字段，
     * 而旧版 MySend 只会拉取 PC 文件，因此在本机归入「已发送」。
     */
    private fun handleRemoteLog(deviceId: String, data: Map<String, Any?>) {
        val name = data.str("name")
        if (name.isEmpty()) return
        val peerName = data.str("peer").ifEmpty { peerNames[deviceId].orEmpty() }
        val sentBucket = bucket(data["sent"])
        val recvBucket = bucket(data["recv"])
        val directional = sentBucket != null || recvBucket != null

        val sentCount: Int
        val sentBytes: Long
        val sentFiles: List<String>
        val receivedCount: Int
        val receivedBytes: Long
        val receivedFiles: List<String>

        if (directional) {
            // 对端发出 -> 本机接收；对端接收 -> 本机发送。
            sentCount = recvBucket?.count ?: 0
            sentBytes = recvBucket?.bytes ?: 0L
            sentFiles = recvBucket?.files.orEmpty()
            receivedCount = sentBucket?.count ?: 0
            receivedBytes = sentBucket?.bytes ?: 0L
            receivedFiles = sentBucket?.files.orEmpty()
        } else {
            sentCount = data.intValue("count")
            sentBytes = data.longValue("bytes")
            sentFiles = data["files"].asList()
                .mapNotNull { it?.toString() }
                .filter { it.isNotEmpty() }
            receivedCount = 0
            receivedBytes = 0L
            receivedFiles = emptyList()
        }

        val total = sentCount + receivedCount
        if (total <= 0) return
        val time = data.longValue("time").takeIf { it > 0 } ?: System.currentTimeMillis()
        runCatching {
            logStore.add(
                SyncLog(
                    task = name,
                    peerName = peerName,
                    startedAt = time,
                    finishedAt = time,
                    total = total,
                    success = total,
                    failed = 0,
                    bytes = sentBytes + receivedBytes,
                    sentCount = sentCount,
                    sentBytes = sentBytes,
                    sentFiles = sentFiles,
                    receivedCount = receivedCount,
                    receivedBytes = receivedBytes,
                    receivedFiles = receivedFiles
                )
            )
        }
        notifyChanged()
    }

    private fun bucket(raw: Any?): LogBucket? {
        if (raw !is Map<*, *>) return null
        val map = raw.asMap()
        val files = (if (map.containsKey("f")) map["f"] else map["files"])
            .asList()
            .mapNotNull { it?.toString() }
            .filter { it.isNotEmpty() }
        val count = if (map.containsKey("c")) map.intValue("c") else map.intValue("count")
        val bytes = if (map.containsKey("b")) map.longValue("b") else map.longValue("bytes")
        return LogBucket(count, bytes, files)
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
        val code = result.json().str("code")
        return if (code.isNotEmpty()) code else "HTTP_${result.status}"
    }

    private fun messageOf(result: HttpClientLite.Result): String {
        val message = result.json().str("message")
        return if (message.isNotEmpty()) message else "HTTP ${result.status}"
    }

    private fun codeOf(result: HttpClientLite.DownloadResult): String {
        val code = result.json().str("code")
        return if (code.isNotEmpty()) code else "HTTP_${result.status}"
    }

    private fun messageOf(result: HttpClientLite.DownloadResult): String {
        val message = result.json().str("message")
        return if (message.isNotEmpty()) message else "HTTP ${result.status}"
    }

    private fun reasonOf(e: Exception): String = when (e) {
        is RemoteException -> e.code
        is SocketTimeoutException -> "TIMEOUT"
        is CancelledException -> "CANCELLED"
        is IOException -> {
            val text = e.message.orEmpty().lowercase(Locale.ROOT)
            if (text.contains("no space") ||
                text.contains("not enough space") ||
                text.contains("enospc")
            ) {
                "NO_SPACE"
            } else {
                "IO_ERROR"
            }
        }

        else -> e.message?.take(60) ?: "IO_ERROR"
    }

    private fun fileTimeoutMs(size: Long): Int {
        val perMiB = size / (1024 * 1024) * 2_000
        return perMiB.coerceIn(60_000L, Constants.MAX_FILE_TIMEOUT_MS.toLong()).toInt()
    }

    private fun notifyChanged() {
        onChanged?.invoke()
    }

    private fun fileName(path: String): String = path.substringAfterLast('/')

    private data class LogBucket(
        val count: Int,
        val bytes: Long,
        val files: List<String>
    )

    companion object {
        private fun randomHex(bytes: Int): String {
            val data = ByteArray(bytes)
            SecureRandom().nextBytes(data)
            return data.joinToString("") {
                String.format(Locale.ROOT, "%02x", it.toInt() and 0xff)
            }
        }
    }
}
