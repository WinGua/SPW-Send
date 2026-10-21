package com.wingua.spwsend.sync

import com.wingua.spwsend.core.Constants
import com.wingua.spwsend.core.Config
import com.wingua.spwsend.core.Json
import com.wingua.spwsend.core.PathUtil
import com.wingua.spwsend.core.asMap
import com.wingua.spwsend.core.intValue
import com.wingua.spwsend.core.str
import com.wingua.spwsend.net.HttpRequest
import com.wingua.spwsend.net.HttpResponse
import java.io.File
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * PC 端 HTTP 端点实现（协议 v2）。
 *
 * PC 侧必须实现 hello / pair / index / log / disconnect 与可选的 /v2/file；
 * 不接受 /v2/upload（同步方向固定为 PC → Android）。
 */
class PcEndpoints(
    private val session: String,
    private val configProvider: () -> Config,
    private val busyProvider: () -> Boolean,
    private val onPeerDisconnected: (String) -> Unit,
    private val onPeerPaired: (String, String?) -> Unit,
    private val onRemoteLog: (Map<String, Any?>) -> Unit
) {

    /** token -> 对端设备 id */
    private val tokens = ConcurrentHashMap<String, String>()

    private val random = SecureRandom()

    fun handle(req: HttpRequest): HttpResponse {
        return when (req.path) {
            "/v2/hello" -> hello(req)
            "/v2/pair" -> pair(req)
            "/v2/index" -> authorized(req) { index(req) }
            "/v2/file" -> authorized(req) { file(req) }
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
                "session" to session,
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
        val data = runCatching { Json.parse(req.bodyText()).asMap() }.getOrNull()
            ?: return error(400, "BAD_REQUEST", "请求体非法")
        if (data.intValue("proto") != Constants.PROTO) {
            return error(426, "PROTO_MISMATCH", "协议版本不一致")
        }
        if (data.str("role") != "android") {
            return error(400, "BAD_REQUEST", "角色不匹配")
        }
        val peerId = data.str("id")
        if (peerId.isEmpty()) return error(400, "BAD_REQUEST", "设备 id 缺失")
        if (busyProvider()) return error(409, "BUSY", "已有同步任务进行中")

        // 同一设备重新配对时，旧 token 立即失效。
        forget(peerId)
        val token = newToken()
        tokens[token] = peerId
        // Android 主动配对时会在请求体附带自己签发的 token，供本机反向调用。
        onPeerPaired(peerId, data.str("token").takeIf { it.isNotEmpty() })
        val config = configProvider()
        return json(
            200,
            Json.obj(
                "ok" to true,
                "token" to token,
                "session" to session,
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
        val name = req.query["name"] ?: ""
        val mapping = configProvider().mappings.firstOrNull { it.name == name }
            ?: return error(404, "MAPPING_NOT_FOUND", "映射「$name」不存在")
        val files = PathUtil.scan(File(mapping.sourcePath))
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
        val name = req.query["name"] ?: ""
        val rel = req.query["path"] ?: ""
        val mapping = configProvider().mappings.firstOrNull { it.name == name }
            ?: return error(404, "MAPPING_NOT_FOUND", "映射「$name」不存在")
        val target = PathUtil.resolveSafely(File(mapping.sourcePath), rel)
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

    private fun log(req: HttpRequest): HttpResponse {
        if (req.method != "POST") return error(405, "BAD_REQUEST", "方法不允许")
        val data = runCatching { Json.parse(req.bodyText()).asMap() }.getOrNull()
            ?: return error(400, "BAD_REQUEST", "请求体非法")
        onRemoteLog(data)
        return json(200, Json.obj("ok" to true))
    }

    private fun disconnect(req: HttpRequest): HttpResponse {
        if (req.method != "POST") return error(405, "BAD_REQUEST", "方法不允许")
        val token = req.header("X-SpwSend-Token") ?: ""
        val deviceId = tokens.remove(token) ?: return error(401, "UNAUTHORIZED", "会话不存在")
        forget(deviceId)
        onPeerDisconnected(deviceId)
        return json(200, Json.obj("ok" to true))
    }

    private fun authorized(req: HttpRequest, block: () -> HttpResponse): HttpResponse {
        // 协议规定除 hello/pair 外，token 缺失或错误一律 401。
        val token = req.header("X-SpwSend-Token")
        if (token == null || !tokens.containsKey(token)) {
            return error(401, "UNAUTHORIZED", "缺少或错误的会话 token")
        }
        protoMismatch(req)?.let { return it }
        return block()
    }

    private fun protoMismatch(req: HttpRequest): HttpResponse? {
        val raw = req.header("X-SpwSend-Proto")?.trim() ?: return null
        val proto = raw.toIntOrNull()
            ?: return error(426, "PROTO_MISMATCH", "协议版本不一致")
        return if (proto != Constants.PROTO) {
            error(426, "PROTO_MISMATCH", "协议版本不一致")
        } else {
            null
        }
    }

    private fun newToken(): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return bytes.joinToString("") { String.format(Locale.ROOT, "%02x", it) }
    }

    private fun json(status: Int, payload: Map<String, Any?>): HttpResponse =
        HttpResponse(status, Json.stringify(payload).toByteArray(Charsets.UTF_8))

    private fun error(status: Int, code: String, message: String): HttpResponse = json(
        status,
        Json.obj("ok" to false, "code" to code, "message" to message)
    )
}
