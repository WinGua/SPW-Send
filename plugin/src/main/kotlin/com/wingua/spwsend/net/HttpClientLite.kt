package com.wingua.spwsend.net

import com.wingua.spwsend.core.Json
import com.wingua.spwsend.core.asMap
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import java.net.URLEncoder
import javax.net.ssl.HttpsURLConnection

/** 严格固定证书指纹的 HTTPS 客户端；完整消费响应后复用连接。 */
class TransportIOException(cause: IOException) : IOException(cause.message, cause)

object HttpClientLite {
    private inline fun <T> network(action: () -> T): T =
        try {
            action()
        } catch (e: TransportIOException) {
            throw e
        } catch (e: IOException) {
            throw TransportIOException(e)
        }

    private class NetworkOutput(private val output: OutputStream) : OutputStream() {
        override fun write(value: Int) = network { output.write(value) }

        override fun write(bytes: ByteArray, offset: Int, length: Int) = network {
            output.write(bytes, offset, length)
        }

        override fun flush() = network { output.flush() }

        override fun close() = network { output.close() }
    }

    private val active =
        java.util.concurrent.ConcurrentHashMap<String, MutableSet<HttpURLConnection>>()
    private val cancelled = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    fun cancelTask(task: String) {
        cancelled.add(task)
        active[task]?.toList()?.forEach { it.disconnect() }
    }

    fun clearTask(task: String) {
        cancelled.remove(task)
    }

    private fun register(task: String?, connection: HttpURLConnection) {
        if (task == null) return
        active.compute(task) { _, connections ->
            if (cancelled.contains(task)) throw IOException("同步已取消")
            (connections ?: java.util.concurrent.ConcurrentHashMap.newKeySet()).apply {
                add(connection)
            }
        }
    }

    private fun unregister(task: String?, connection: HttpURLConnection) {
        if (task != null)
            active.computeIfPresent(task) { _, connections ->
                connections.remove(connection)
                connections.takeIf { it.isNotEmpty() }
            }
    }

    data class Result(val status: Int, val body: ByteArray, val headers: Map<String, String>) {
        fun text(): String = String(body, Charsets.UTF_8)

        fun json(): Map<String, Any?> = Json.parse(text()).asMap()
    }

    /** 流式下载结果；[body] 仅在非 2xx 时保存错误 JSON。 */
    data class DownloadResult(
        val status: Int,
        val body: ByteArray,
        val received: Long,
        val headers: Map<String, String>,
    ) {
        fun json(): Map<String, Any?> = Json.parse(String(body, Charsets.UTF_8)).asMap()
    }

    fun get(
        host: String,
        fingerprint: String,
        port: Int,
        path: String,
        query: Map<String, String> = emptyMap(),
        headers: Map<String, String> = emptyMap(),
        readTimeoutMs: Int = 10_000,
    ): Result =
        execute("GET", host, fingerprint, port, path, query, headers, null, -1, readTimeoutMs, null)

    fun post(
        host: String,
        fingerprint: String,
        port: Int,
        path: String,
        query: Map<String, String> = emptyMap(),
        headers: Map<String, String> = emptyMap(),
        body: ByteArray? = ByteArray(0),
        readTimeoutMs: Int = 10_000,
    ): Result =
        execute(
            "POST",
            host,
            fingerprint,
            port,
            path,
            query,
            headers,
            body,
            -1,
            readTimeoutMs,
            null,
        )

    /**
     * 流式上传固定长度请求体。
     *
     * [length] 必须与实际写入字节数一致，否则底层连接会报错； [writer] 在连接建立后写入文件内容。
     */
    fun postStream(
        host: String,
        fingerprint: String,
        port: Int,
        path: String,
        query: Map<String, String> = emptyMap(),
        headers: Map<String, String> = emptyMap(),
        length: Long,
        readTimeoutMs: Int = 60_000,
        writer: (OutputStream) -> Unit,
    ): Result =
        execute(
            "POST",
            host,
            fingerprint,
            port,
            path,
            query,
            headers,
            null,
            length,
            readTimeoutMs,
            writer,
        )

    /**
     * 流式下载到 [target]。
     *
     * - [rangeStart] 大于 0 时发送 `Range: bytes=n-`
     * - 仅当对端返回 206 且 [append] 为 true 时追加，否则从 0 覆盖
     * - 每读一块都会调用 [checkCancelled]，取消时抛出调用方提供的异常
     */
    fun download(
        host: String,
        fingerprint: String,
        port: Int,
        path: String,
        query: Map<String, String> = emptyMap(),
        headers: Map<String, String> = emptyMap(),
        rangeStart: Long = 0L,
        readTimeoutMs: Int = 60_000,
        target: File,
        append: Boolean,
        checkCancelled: () -> Unit,
        onBytes: (Int) -> Unit = {},
        openTarget: ((Boolean) -> OutputStream)? = null,
    ): DownloadResult {
        val url = buildUrl(host, fingerprint, port, path, query)
        val conn = url.openConnection(Proxy.NO_PROXY) as HttpsURLConnection
        conn.sslSocketFactory = PinnedTls.factory(fingerprint)
        conn.hostnameVerifier =
            javax.net.ssl.HostnameVerifier { requested, session ->
                requested == host &&
                    TransferIdentity.hash(
                        session.peerCertificates.first() as java.security.cert.X509Certificate
                    ) == fingerprint
            }
        val task =
            headers["X-MySend-Sync"].takeUnless {
                path.startsWith("/v2/sync/") || path == "/v2/log"
            }
        register(task, conn)
        var reusable = false
        try {
            conn.requestMethod = "GET"
            conn.connectTimeout = 8_000
            conn.readTimeout = readTimeoutMs.coerceAtLeast(3_000)
            conn.useCaches = false
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("Accept-Encoding", "identity")
            if (rangeStart > 0) {
                conn.setRequestProperty("Range", "bytes=$rangeStart-")
            }
            for ((k, v) in headers) conn.setRequestProperty(k, v)

            val status = network { conn.responseCode }
            val responseHeaders = LinkedHashMap<String, String>()
            for ((key, values) in conn.headerFields) {
                if (key == null) continue
                responseHeaders[key.lowercase()] = values.joinToString(", ")
            }
            if (status !in 200..299) {
                val error =
                    try {
                        conn.errorStream?.use { it.readBytes() } ?: ByteArray(0)
                    } catch (_: Exception) {
                        ByteArray(0)
                    }
                reusable = true
                return DownloadResult(status, error, 0L, responseHeaders)
            }

            if (openTarget == null) target.parentFile?.mkdirs()
            val appendFile = append && status == 206
            var received = 0L
            val input = network { conn.inputStream }
            try {
                (openTarget?.invoke(appendFile) ?: FileOutputStream(target, appendFile)).use { out ->
                    val buffer = ByteArray(512 * 1024)
                    while (true) {
                        checkCancelled()
                        val read = network { input.read(buffer) }
                        if (read <= 0) break
                        out.write(buffer, 0, read)
                        received += read
                        onBytes(read)
                    }
                    out.flush()
                }
            } finally {
                runCatching { input.close() }
            }
            val total = if (appendFile && openTarget == null) target.length() else if (appendFile) rangeStart + received else received
            reusable = true
            return DownloadResult(status, ByteArray(0), total, responseHeaders)
        } finally {
            unregister(task, conn)
            if (!reusable) conn.disconnect()
        }
    }

    private fun execute(
        method: String,
        host: String,
        fingerprint: String,
        port: Int,
        path: String,
        query: Map<String, String>,
        headers: Map<String, String>,
        body: ByteArray?,
        streamLength: Long,
        readTimeoutMs: Int,
        writer: ((OutputStream) -> Unit)?,
    ): Result {
        val url = buildUrl(host, fingerprint, port, path, query)
        val conn = url.openConnection(Proxy.NO_PROXY) as HttpsURLConnection
        conn.sslSocketFactory = PinnedTls.factory(fingerprint)
        conn.hostnameVerifier =
            javax.net.ssl.HostnameVerifier { requested, session ->
                requested == host &&
                    TransferIdentity.hash(
                        session.peerCertificates.first() as java.security.cert.X509Certificate
                    ) == fingerprint
            }
        val task =
            headers["X-MySend-Sync"].takeUnless {
                path.startsWith("/v2/sync/") || path == "/v2/log"
            }
        register(task, conn)
        var reusable = false
        try {
            conn.requestMethod = method
            conn.connectTimeout = 8_000
            conn.readTimeout = readTimeoutMs.coerceAtLeast(3_000)
            conn.useCaches = false
            conn.instanceFollowRedirects = false
            // MySend 服务端会按 Accept-Encoding 压缩 JSON；显式要求未压缩 JSON 以免需要解压。
            conn.setRequestProperty("Accept-Encoding", "identity")
            for ((k, v) in headers) conn.setRequestProperty(k, v)

            if (writer != null) {
                conn.doOutput = true
                conn.setFixedLengthStreamingMode(streamLength)
                var writeError: IOException? = null
                try {
                    NetworkOutput(network { conn.outputStream }).use { out ->
                        writer(out)
                        out.flush()
                    }
                } catch (e: IOException) {
                    if (e !is TransportIOException) throw e
                    writeError = e
                }
                val status =
                    try {
                        network { conn.responseCode }
                    } catch (e: IOException) {
                        throw writeError ?: e
                    }
                return network { readResult(conn, status) }.also { reusable = true }
            }

            if (body != null) {
                conn.doOutput = true
                conn.setFixedLengthStreamingMode(body.size)
                network { conn.outputStream.use { it.write(body) } }
            } else if (method != "GET") {
                conn.doOutput = true
                conn.setFixedLengthStreamingMode(0L)
            }

            return network { readResult(conn, conn.responseCode) }.also { reusable = true }
        } finally {
            unregister(task, conn)
            if (!reusable) conn.disconnect()
        }
    }

    private fun readResult(conn: HttpURLConnection, status: Int): Result {
        val stream = if (status in 200..299) conn.inputStream else conn.errorStream
        val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)
        val headers = LinkedHashMap<String, String>()
        for ((key, values) in conn.headerFields) {
            if (key == null) continue
            headers[key.lowercase()] = values.joinToString(", ")
        }
        return Result(status, bytes, headers)
    }

    private fun buildUrl(
        host: String,
        fingerprint: String,
        port: Int,
        path: String,
        query: Map<String, String>,
    ): URL {
        PinnedTls.factory(fingerprint)
        val encoded =
            query.entries.joinToString("&") { (k, v) ->
                "${encode(k)}=${encode(v)}"
            }
        val suffix = if (encoded.isEmpty()) "" else "?$encoded"
        return java.net.URI("https://$host:$port$path$suffix").toURL()
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
}
