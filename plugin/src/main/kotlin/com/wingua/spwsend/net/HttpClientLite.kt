package com.wingua.spwsend.net

import com.wingua.spwsend.core.Json
import com.wingua.spwsend.core.asMap
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** 极简 HTTP 客户端（基于 JDK [HttpURLConnection]，每次短连接）。 */
object HttpClientLite {

    data class Result(val status: Int, val body: ByteArray, val headers: Map<String, String>) {
        fun text(): String = String(body, Charsets.UTF_8)

        fun json(): Map<String, Any?> =
            runCatching { Json.parse(text()).asMap() }.getOrDefault(emptyMap())
    }

    fun get(
        host: String,
        port: Int,
        path: String,
        query: Map<String, String> = emptyMap(),
        headers: Map<String, String> = emptyMap(),
        readTimeoutMs: Int = 10_000
    ): Result = execute("GET", host, port, path, query, headers, null, -1, readTimeoutMs, null)

    fun post(
        host: String,
        port: Int,
        path: String,
        query: Map<String, String> = emptyMap(),
        headers: Map<String, String> = emptyMap(),
        body: ByteArray? = ByteArray(0),
        readTimeoutMs: Int = 10_000
    ): Result = execute("POST", host, port, path, query, headers, body, -1, readTimeoutMs, null)

    /**
     * 流式上传固定长度请求体。
     *
     * [length] 必须与实际写入字节数一致，否则底层连接会报错；
     * [writer] 在连接建立后写入文件内容。
     */
    fun postStream(
        host: String,
        port: Int,
        path: String,
        query: Map<String, String> = emptyMap(),
        headers: Map<String, String> = emptyMap(),
        length: Long,
        readTimeoutMs: Int = 60_000,
        writer: (OutputStream) -> Unit
    ): Result = execute("POST", host, port, path, query, headers, null, length, readTimeoutMs, writer)

    private fun execute(
        method: String,
        host: String,
        port: Int,
        path: String,
        query: Map<String, String>,
        headers: Map<String, String>,
        body: ByteArray?,
        streamLength: Long,
        readTimeoutMs: Int,
        writer: ((OutputStream) -> Unit)?
    ): Result {
        val url = buildUrl(host, port, path, query)
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 8_000
            conn.readTimeout = readTimeoutMs.coerceAtLeast(3_000)
            conn.useCaches = false
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("Connection", "close")
            // MySend 服务端会按 Accept-Encoding 压缩 JSON；显式要求明文以免需要解压。
            conn.setRequestProperty("Accept-Encoding", "identity")
            for ((k, v) in headers) conn.setRequestProperty(k, v)

            if (writer != null) {
                conn.doOutput = true
                conn.setFixedLengthStreamingMode(streamLength)
                var writeError: IOException? = null
                try {
                    conn.outputStream.use { out ->
                        writer(out)
                        out.flush()
                    }
                } catch (e: IOException) {
                    writeError = e
                }
                val status = try {
                    conn.responseCode
                } catch (e: IOException) {
                    throw writeError ?: e
                }
                return readResult(conn, status)
            }

            if (body != null) {
                conn.doOutput = true
                conn.setFixedLengthStreamingMode(body.size)
                conn.outputStream.use { it.write(body) }
            } else if (method != "GET") {
                conn.doOutput = true
                conn.setFixedLengthStreamingMode(0L)
            }

            return readResult(conn, conn.responseCode)
        } finally {
            conn.disconnect()
        }
    }

    private fun readResult(conn: HttpURLConnection, status: Int): Result {
        val stream = if (status in 200..299) conn.inputStream else conn.errorStream
        val bytes = try {
            stream?.use { it.readBytes() } ?: ByteArray(0)
        } catch (_: Exception) {
            ByteArray(0)
        }
        val headers = LinkedHashMap<String, String>()
        for ((key, values) in conn.headerFields) {
            if (key == null) continue
            headers[key.lowercase()] = values.joinToString(", ")
        }
        return Result(status, bytes, headers)
    }

    private fun buildUrl(
        host: String,
        port: Int,
        path: String,
        query: Map<String, String>
    ): URL {
        val encoded = query.entries.joinToString("&") { (k, v) ->
            "${encode(k)}=${encode(v)}"
        }
        val suffix = if (encoded.isEmpty()) "" else "?$encoded"
        return URL("http://$host:$port$path$suffix")
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
}
