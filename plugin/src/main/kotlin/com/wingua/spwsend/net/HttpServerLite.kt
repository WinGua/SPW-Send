package com.wingua.spwsend.net

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** 服务端收到的单个 HTTP 请求。 */
class HttpRequest(
    val method: String,
    val path: String,
    val query: Map<String, String>,
    val headers: Map<String, String>,
    private val input: InputStream,
    private val contentLength: Long
) {
    fun header(name: String): String? = headers[name.lowercase()]

    /** 读取请求体（最多 [maxBytes] 字节，防止恶意超大 JSON）。 */
    fun bodyBytes(maxBytes: Int = 2 * 1024 * 1024): ByteArray {
        if (contentLength <= 0) return ByteArray(0)
        val size = minOf(contentLength, maxBytes.toLong()).toInt()
        val buffer = ByteArray(size)
        var read = 0
        while (read < size) {
            val n = input.read(buffer, read, size - read)
            if (n < 0) break
            read += n
        }
        return if (read == size) buffer else buffer.copyOf(read)
    }

    fun bodyText(): String = String(bodyBytes(), Charsets.UTF_8)
}

/** 服务端返回的响应。 */
class HttpResponse(
    val status: Int,
    val body: ByteArray? = null,
    val contentType: String? = "application/json; charset=utf-8",
    val file: File? = null,
    val rangeStart: Long = 0,
    val extraHeaders: Map<String, String> = emptyMap()
)

/**
 * 极简 HTTP/1.1 服务端：固定短连接、按 Content-Length 读取、支持文件与 Range 输出。
 *
 * 仅监听局域网内的控制与文件请求，不实现 keep-alive、分块传输与 TLS。
 */
class HttpServerLite(private val handler: (HttpRequest) -> HttpResponse) {

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var running = false

    private val executor = Executors.newFixedThreadPool(6) { runnable ->
        Thread(runnable, "spw-send-http").apply { isDaemon = true }
    }

    private val activeSockets = ConcurrentHashMap.newKeySet<Socket>()

    val port: Int
        get() = serverSocket?.localPort ?: 0

    val isRunning: Boolean
        get() = running

    /** 启动服务；[preferredPort] 被占用时自动改用系统分配的端口。返回实际端口。 */
    fun start(preferredPort: Int): Int {
        if (running) return port
        val socket = try {
            bindSocket(preferredPort)
        } catch (_: IOException) {
            bindSocket(0)
        }
        serverSocket = socket
        running = true
        Thread({ acceptLoop(socket) }, "spw-send-http-accept").apply {
            isDaemon = true
            start()
        }
        return socket.localPort
    }

    private fun bindSocket(port: Int): ServerSocket = ServerSocket().apply {
        reuseAddress = true
        bind(InetSocketAddress(port), 50)
    }

    fun stop() {
        running = false
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
        for (client in activeSockets) {
            try {
                client.close()
            } catch (_: Exception) {
            }
        }
        activeSockets.clear()
        executor.shutdownNow()
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (running) {
            val client = try {
                socket.accept()
            } catch (_: IOException) {
                if (!running) break else continue
            }
            activeSockets.add(client)
            try {
                executor.execute { handle(client) }
            } catch (_: Exception) {
                activeSockets.remove(client)
                try {
                    client.close()
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun handle(socket: Socket) {
        try {
            socket.use { s ->
                try {
                    s.soTimeout = 60_000
                    val input = BufferedInputStream(s.getInputStream())
                    val requestLine = readLine(input) ?: return
                    val parts = requestLine.split(' ').filter { it.isNotEmpty() }
                    if (parts.size < 2) return
                    val method = parts[0].uppercase()
                    val target = parts[1]

                    val headers = LinkedHashMap<String, String>()
                    while (true) {
                        val line = readLine(input) ?: break
                        if (line.isEmpty()) break
                        val idx = line.indexOf(':')
                        if (idx <= 0) continue
                        headers[line.substring(0, idx).trim().lowercase()] =
                            line.substring(idx + 1).trim()
                    }

                    val qIndex = target.indexOf('?')
                    val rawPath = if (qIndex >= 0) target.substring(0, qIndex) else target
                    val rawQuery = if (qIndex >= 0) target.substring(qIndex + 1) else ""
                    val path = runCatching { URLDecoder.decode(rawPath, "UTF-8") }
                        .getOrDefault(rawPath)
                    val query = parseQuery(rawQuery)
                    val length = headers["content-length"]?.toLongOrNull() ?: 0L

                    val request = HttpRequest(method, path, query, headers, input, length)
                    val response = try {
                        handler(request)
                    } catch (e: Exception) {
                        HttpResponse(
                            status = 500,
                            body = """{"ok":false,"code":"IO_ERROR","message":"${escape(e.message ?: "内部错误")}"}"""
                                .toByteArray(Charsets.UTF_8)
                        )
                    }
                    writeResponse(BufferedOutputStream(s.getOutputStream()), response, method)
                } catch (_: Exception) {
                    // 单个连接异常不影响服务整体。
                }
            }
        } finally {
            activeSockets.remove(socket)
        }
    }

    private fun writeResponse(out: OutputStream, response: HttpResponse, method: String) {
        val head = StringBuilder(256)
        head.append("HTTP/1.1 ").append(response.status).append(' ')
            .append(REASONS[response.status] ?: "OK").append("\r\n")
        head.append("Connection: close\r\n")

        val file = response.file
        if (file != null) {
            val total = file.length()
            val start = response.rangeStart.coerceIn(0L, total)
            val length = total - start
            response.contentType?.let { head.append("Content-Type: ").append(it).append("\r\n") }
            head.append("Accept-Ranges: bytes\r\n")
            head.append("Content-Length: ").append(length).append("\r\n")
            if (response.status == 206) {
                head.append("Content-Range: bytes ").append(start).append('-')
                    .append(if (total > 0) total - 1 else 0L).append('/').append(total)
                    .append("\r\n")
            }
        } else {
            val body = response.body ?: ByteArray(0)
            if (body.isNotEmpty() && response.contentType != null) {
                head.append("Content-Type: ").append(response.contentType).append("\r\n")
            }
            head.append("Content-Length: ").append(body.size).append("\r\n")
        }

        for ((k, v) in response.extraHeaders) {
            head.append(sanitize(k)).append(": ").append(sanitize(v)).append("\r\n")
        }
        head.append("\r\n")
        out.write(head.toString().toByteArray(Charsets.UTF_8))
        out.flush()

        if (method == "HEAD") return

        if (file != null) {
            if (!file.isFile) return
            val start = response.rangeStart.coerceIn(0L, file.length())
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(start)
                copy(raf, out)
            }
        } else {
            response.body?.let { out.write(it) }
        }
        out.flush()
    }

    private fun copy(source: RandomAccessFile, out: OutputStream) {
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = source.read(buffer)
            if (read <= 0) break
            out.write(buffer, 0, read)
        }
    }

    private fun readLine(input: InputStream): String? {
        val buffer = ByteArrayOutputStream(64)
        var b = input.read()
        if (b < 0) return null
        while (b >= 0 && b != '\n'.code) {
            if (b != '\r'.code && buffer.size() < 8192) buffer.write(b)
            b = input.read()
        }
        return buffer.toString("UTF-8")
    }

    private fun parseQuery(raw: String): Map<String, String> {
        if (raw.isEmpty()) return emptyMap()
        val result = LinkedHashMap<String, String>()
        for (pair in raw.split('&')) {
            if (pair.isEmpty()) continue
            val i = pair.indexOf('=')
            val key = if (i >= 0) pair.substring(0, i) else pair
            val value = if (i >= 0) pair.substring(i + 1) else ""
            result[runCatching { URLDecoder.decode(key, "UTF-8") }.getOrDefault(key)] =
                runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
        }
        return result
    }

    private fun sanitize(value: String): String =
        value.replace("\r", "").replace("\n", "")

    private fun escape(value: String): String = buildString(value.length + 8) {
        for (c in value) {
            when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c.code < 0x20) append(' ') else append(c)
            }
        }
    }

    companion object {
        private val REASONS = mapOf(
            200 to "OK",
            204 to "No Content",
            206 to "Partial Content",
            400 to "Bad Request",
            401 to "Unauthorized",
            403 to "Forbidden",
            404 to "Not Found",
            405 to "Method Not Allowed",
            409 to "Conflict",
            416 to "Range Not Satisfiable",
            426 to "Upgrade Required",
            500 to "Internal Server Error",
            507 to "Insufficient Storage"
        )
    }
}
