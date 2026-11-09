package com.wingua.spwsend.sync

import com.wingua.spwsend.core.Constants
import com.wingua.spwsend.core.Config
import com.wingua.spwsend.core.Json
import com.wingua.spwsend.core.PathUtil
import com.wingua.spwsend.core.asList
import com.wingua.spwsend.core.asMap
import com.wingua.spwsend.core.intValue
import com.wingua.spwsend.core.str
import com.wingua.spwsend.net.HttpRequest
import com.wingua.spwsend.net.HttpResponse
import java.io.File
import java.io.FileOutputStream
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * PC 端 HTTP 端点实现（协议 v2）。
 *
 * 与 MySend 2.0 完全对称：既能把本机文件提供给对端拉取，也能接收对端推送的分片。
 * 上传目标已存在时返回 `409 ALREADY_EXISTS`，绝不覆盖。
 */
class PcEndpoints(
    private val sessionProvider: () -> String,
    private val configProvider: () -> Config,
    private val busyProvider: () -> Boolean,
    private val onPeerDisconnected: (String) -> Unit,
    private val onPeerPaired: (String, String, String?) -> Unit,
    private val onRemoteLog: (String, Map<String, Any?>) -> Unit,
    private val onUploadStateChanged: (Boolean) -> Unit = {}
) {

    /** token -> 对端设备 id */
    private val tokens = ConcurrentHashMap<String, String>()

    /** 同一时间只允许一个入站上传，避免并发写同一目录。 */
    private val uploading = AtomicBoolean(false)

    private val random = SecureRandom()

    /** 是否有入站上传正在写盘，供同步核心做并发判断。 */
    val isUploading: Boolean
        get() = uploading.get()

    fun handle(req: HttpRequest): HttpResponse {
        return when (req.path) {
            "/v2/hello" -> hello(req)
            "/v2/pair" -> pair(req)
            "/v2/index" -> authorized(req) { index(req) }
            "/v2/file" -> authorized(req) { file(req) }
            "/v2/resume" -> authorized(req) { resume(req) }
            "/v2/resume/batch" -> authorized(req) { resumeBatch(req) }
            "/v2/upload" -> authorized(req) { upload(req) }
            "/v2/log" -> authorized(req) { log(req) }
            "/v2/disconnect" -> authorized(req) { disconnect(req) }
            else -> error(404, "NOT_FOUND", "未知端点")
        }
    }

    /** 使某台设备的所有 token 失效。 */
    fun forget(deviceId: String) {
        for (key in tokens.keys.filter { tokens[it] == deviceId }) {
            tokens.remove(key)
        }
    }

    /**
     * 为本机签发一个供 [deviceId] 反向调用的 token。
     *
     * 主动配对时把该 token 放进请求体，MySend 安卓端据此可直接调用本机，
     * 无需等手机端再主动配对一次。
     */
    fun issueTokenFor(deviceId: String): String {
        forget(deviceId)
        val token = newToken()
        tokens[token] = deviceId
        return token
    }

    /** 服务下线时清空全部会话。 */
    fun clearTokens() {
        tokens.clear()
    }

    private fun hello(req: HttpRequest): HttpResponse {
        if (req.method != "GET") return error(405, "BAD_REQUEST", "方法不允许")
        protoMismatch(req)?.let { return it }
        val config = configProvider()
        return json(
            200,
            Json.obj(
                "proto" to Constants.PROTO,
                "app" to Constants.APP_ID,
                "version" to Constants.APP_VERSION,
                "minPeer" to Constants.MIN_PEER,
                "session" to sessionProvider(),
                "device" to Json.obj(
                    "id" to config.deviceId,
                    "name" to config.deviceName,
                    "role" to "pc"
                )
            )
        )
    }

    private fun pair(req: HttpRequest): HttpResponse {
        if (req.method != "POST") return error(405, "BAD_REQUEST", "方法不允许")
        protoMismatch(req)?.let { return it }
        val data = parseBody(req) ?: return error(400, "BAD_REQUEST", "请求体非法")
        if (data.intValue("proto") != Constants.PROTO) {
            return error(426, "PROTO_MISMATCH", "协议版本不一致")
        }
        if (data.str("role") != "android") {
            return error(400, "BAD_REQUEST", "角色不匹配")
        }
        val peerId = data.str("id")
        if (peerId.isEmpty()) return error(400, "BAD_REQUEST", "设备 id 缺失")
        if (busyProvider() || uploading.get()) return error(409, "BUSY", "已有同步任务进行中")

        // 同一设备重新配对时，旧 token 立即失效。
        forget(peerId)
        val token = newToken()
        tokens[token] = peerId
        // 对端主动配对时会在请求体附带自己签发的 token，供本机反向调用。
        onPeerPaired(peerId, data.str("name"), data.str("token").takeIf { it.isNotEmpty() })
        val config = configProvider()
        return json(
            200,
            Json.obj(
                "ok" to true,
                "token" to token,
                "session" to sessionProvider(),
                "device" to Json.obj(
                    "id" to config.deviceId,
                    "name" to config.deviceName,
                    "role" to "pc"
                )
            )
        )
    }

    private fun index(req: HttpRequest): HttpResponse {
        if (req.method != "GET") return error(405, "BAD_REQUEST", "方法不允许")
        if (busyProvider() || uploading.get()) return error(409, "BUSY", "正在同步中")
        val name = req.query["name"] ?: ""
        val mapping = mappingOf(name)
            ?: return error(404, "MAPPING_NOT_FOUND", "映射「$name」不存在")
        val root = File(mapping.sourcePath)
        if (!root.isDirectory) {
            return error(409, "MAPPING_NOT_CONFIGURED", "映射目录不可用")
        }
        val files = PathUtil.scan(root)
        return json(
            200,
            Json.obj(
                "ok" to true,
                "name" to mapping.name,
                "files" to files.map {
                    Json.obj("p" to it.relativePath, "s" to it.size, "m" to it.mtimeMs)
                }
            )
        )
    }

    private fun file(req: HttpRequest): HttpResponse {
        if (req.method != "GET") return error(405, "BAD_REQUEST", "方法不允许")
        if (busyProvider() || uploading.get()) return error(409, "BUSY", "正在同步中")
        val name = req.query["name"] ?: ""
        val rel = req.query["path"] ?: ""
        val mapping = mappingOf(name)
            ?: return error(404, "MAPPING_NOT_FOUND", "映射「$name」不存在")
        val root = File(mapping.sourcePath)
        if (!root.isDirectory) {
            return error(409, "MAPPING_NOT_CONFIGURED", "映射目录不可用")
        }
        val target = PathUtil.resolveSafely(root, rel)
            ?: return error(400, "PATH_INVALID", "路径非法")
        if (!target.isFile) return error(404, "FILE_NOT_FOUND", "文件不存在")

        val total = target.length()
        val range = req.header("range")
        if (range != null) {
            val match = Regex("""^bytes=(\d+)-$""", RegexOption.IGNORE_CASE).find(range.trim())
            val start = match?.groupValues?.get(1)?.toLongOrNull()
                ?: return error(416, "RANGE_INVALID", "Range 格式非法")
            if (start >= total) {
                return error(416, "RANGE_INVALID", "Range 起点越界")
            }
            return HttpResponse(
                status = 206,
                body = null,
                contentType = "application/octet-stream",
                file = target,
                rangeStart = start
            )
        }
        return HttpResponse(
            status = 200,
            contentType = "application/octet-stream",
            file = target
        )
    }

    private fun resume(req: HttpRequest): HttpResponse {
        if (req.method != "GET") return error(400, "BAD_REQUEST", "方法不支持")
        if (busyProvider() || uploading.get()) return error(409, "BUSY", "正在同步中")
        val mapping = mappingOf(req.query["name"] ?: "")
            ?: return error(404, "MAPPING_NOT_FOUND", "映射不存在")
        val rel = req.query["path"] ?: ""
        val size = req.query["size"]?.toLongOrNull()
        val mtime = req.query["mtime"]?.toLongOrNull()
        if (!PathUtil.isValidRelative(rel) || size == null || size < 0 || mtime == null) {
            return error(400, "PATH_INVALID", "路径或大小非法")
        }
        val root = File(mapping.sourcePath)
        if (!root.isDirectory) {
            return error(409, "MAPPING_NOT_CONFIGURED", "映射目录不可用")
        }
        val offset = PartStore.currentOffset(root, rel, size, mtime)
        return json(200, Json.obj("ok" to true, "offset" to offset))
    }

    /** 批量查询续传偏移：请求体 `{name, items:[{p,s,m}]}`。 */
    private fun resumeBatch(req: HttpRequest): HttpResponse {
        if (req.method != "POST") return error(400, "BAD_REQUEST", "方法不支持")
        if (busyProvider() || uploading.get()) return error(409, "BUSY", "正在同步中")
        val data = parseBody(req) ?: return error(400, "BAD_REQUEST", "请求体非法")
        val mapping = mappingOf(data.str("name"))
            ?: return error(404, "MAPPING_NOT_FOUND", "映射不存在")
        val items = data["items"].asList()
        if (items.size > 5000) return error(400, "BAD_REQUEST", "文件列表非法")
        val root = File(mapping.sourcePath)
        if (!root.isDirectory) {
            return error(409, "MAPPING_NOT_CONFIGURED", "映射目录不可用")
        }
        val offsets = LinkedHashMap<String, Long>()
        for (raw in items) {
            val item = raw.asMap()
            val rel = item.str("p")
            val sizeValue = item["s"] as? Number ?: continue
            val mtimeValue = item["m"] as? Number ?: continue
            val size = sizeValue.toLong()
            val mtime = mtimeValue.toLong()
            if (!PathUtil.isValidRelative(rel) || size < 0) continue
            offsets[rel] = PartStore.currentOffset(root, rel, size, mtime)
        }
        return json(200, Json.obj("ok" to true, "offsets" to offsets))
    }

    private fun upload(req: HttpRequest): HttpResponse {
        if (req.method != "POST") return error(400, "BAD_REQUEST", "方法不支持")
        val mapping = mappingOf(req.query["name"] ?: "")
        if (mapping == null) {
            req.drainBody()
            return error(404, "MAPPING_NOT_FOUND", "映射不存在")
        }
        val rel = req.query["path"] ?: ""
        val size = req.query["size"]?.toLongOrNull()
        val mtime = req.query["mtime"]?.toLongOrNull() ?: 0L
        if (!PathUtil.isValidRelative(rel) || size == null || size < 0) {
            req.drainBody()
            return error(400, "PATH_INVALID", "路径或大小非法")
        }
        val root = File(mapping.sourcePath)
        if (!root.isDirectory) {
            req.drainBody()
            return error(409, "MAPPING_NOT_CONFIGURED", "映射目录不可用")
        }
        val dst = PathUtil.resolveSafely(root, rel)
        if (dst == null) {
            req.drainBody()
            return error(400, "PATH_INVALID", "路径非法")
        }
        if (busyProvider() || !uploading.compareAndSet(false, true)) {
            req.drainBody()
            return error(409, "BUSY", "已有同步任务正在进行")
        }
        onUploadStateChanged(true)
        try {
            // 双向同步只补新增：目标已存在时绝不覆盖。
            if (dst.exists()) {
                req.drainBody()
                return error(409, "ALREADY_EXISTS", "目标文件已存在，已跳过")
            }
            val currentOffset = PartStore.currentOffset(root, rel, size, mtime)
            val start = req.header("x-offset")?.toLongOrNull() ?: -1L
            if (start < 0 || start != currentOffset) {
                req.drainBody()
                return error(416, "RANGE_INVALID", "续传偏移不匹配")
            }
            if (req.contentLength != size - start) {
                req.drainBody()
                return error(416, "RANGE_INVALID", "请求体长度与剩余字节不一致")
            }

            dst.parentFile?.mkdirs()
            val partDir = File(root, Constants.PART_DIR)
            if (!partDir.exists()) partDir.mkdirs()

            if (start == 0L) PartStore.discard(root, rel)
            PartStore.writeMeta(root, rel, PartMeta(rel, size, mtime))

            val part = PartStore.partFile(root, rel)
            var received = start
            FileOutputStream(part, start > 0).use { out ->
                val input = req.bodyStream()
                val buffer = ByteArray(64 * 1024)
                var remaining = size - start
                while (remaining > 0) {
                    val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                    val read = input.read(buffer, 0, toRead)
                    if (read <= 0) break
                    out.write(buffer, 0, read)
                    received += read
                    remaining -= read
                }
                out.flush()
            }

            if (received != size) {
                // 数据不完整：保留分片，允许下次续传。
                return error(409, "INCOMPLETE", "数据不完整，已保留分片")
            }
            if (dst.exists()) {
                PartStore.discard(root, rel)
                return error(409, "ALREADY_EXISTS", "目标文件已存在，已跳过")
            }
            if (!PartStore.promote(part, dst)) {
                PartStore.discard(root, rel)
                return error(409, "ALREADY_EXISTS", "目标文件已存在，已跳过")
            }
            if (mtime > 0) runCatching { dst.setLastModified(mtime) }
            PartStore.discard(root, rel)
            return json(200, Json.obj("ok" to true, "size" to size))
        } catch (e: Exception) {
            return if (isNoSpace(e)) {
                error(507, "NO_SPACE", "目标磁盘空间不足")
            } else {
                error(500, "IO_ERROR", e.message ?: "写入失败")
            }
        } finally {
            uploading.set(false)
            onUploadStateChanged(false)
        }
    }

    private fun log(req: HttpRequest): HttpResponse {
        if (req.method != "POST") return error(405, "BAD_REQUEST", "方法不允许")
        val data = parseBody(req, Constants.LOG_MAX_BYTES)
            ?: return error(400, "BAD_REQUEST", "请求体非法")
        val token = req.header(Constants.HEADER_TOKEN) ?: ""
        val deviceId = tokens[token] ?: ""
        onRemoteLog(deviceId, data)
        return json(200, Json.obj("ok" to true))
    }

    private fun disconnect(req: HttpRequest): HttpResponse {
        if (req.method != "POST") return error(405, "BAD_REQUEST", "方法不允许")
        val token = req.header(Constants.HEADER_TOKEN) ?: ""
        val deviceId = tokens.remove(token) ?: return error(401, "UNAUTHORIZED", "会话不存在")
        forget(deviceId)
        onPeerDisconnected(deviceId)
        return json(200, Json.obj("ok" to true))
    }

    private fun authorized(req: HttpRequest, block: () -> HttpResponse): HttpResponse {
        // 协议规定除 hello/pair 外，token 缺失或错误一律 401。
        val token = req.header(Constants.HEADER_TOKEN)
        if (token == null || !tokens.containsKey(token)) {
            return error(401, "UNAUTHORIZED", "缺少或错误的会话 token")
        }
        protoMismatch(req)?.let { return it }
        return block()
    }

    private fun protoMismatch(req: HttpRequest): HttpResponse? {
        val raw = req.header(Constants.HEADER_PROTO)?.trim() ?: return null
        val proto = raw.toIntOrNull()
            ?: return error(426, "PROTO_MISMATCH", "协议版本不一致")
        return if (proto != Constants.PROTO) {
            error(426, "PROTO_MISMATCH", "协议版本不一致")
        } else {
            null
        }
    }

    private fun parseBody(
        req: HttpRequest,
        maxBytes: Int = 2 * 1024 * 1024
    ): Map<String, Any?>? = runCatching {
        Json.parse(String(req.bodyBytes(maxBytes), Charsets.UTF_8)).asMap()
    }.getOrNull()

    private fun mappingOf(name: String) =
        configProvider().mappings.firstOrNull { it.name == name }

    private fun isNoSpace(e: Exception): Boolean {
        val text = e.message.orEmpty().lowercase(Locale.ROOT)
        return text.contains("no space") ||
            text.contains("enospc") ||
            text.contains("not enough space") ||
            text.contains("errno = 28")
    }

    private fun newToken(): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return bytes.joinToString("") {
            String.format(Locale.ROOT, "%02x", it.toInt() and 0xff)
        }
    }

    private fun json(status: Int, payload: Map<String, Any?>): HttpResponse =
        HttpResponse(status, Json.stringify(payload).toByteArray(Charsets.UTF_8))

    private fun error(status: Int, code: String, message: String): HttpResponse = json(
        status,
        Json.obj("ok" to false, "code" to code, "message" to message)
    )
}
