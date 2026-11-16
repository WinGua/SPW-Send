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
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

/** 服务端收到的单个 HTTP 请求。 */
class HttpRequest(
    val method: String,
    val path: String,
    val query: Map<String, String>,
    val headers: Map<String, String>,
    private val input: InputStream,
    val contentLength: Long,
    val remoteAddress: String = "",
    val remotePort: Int = 0,
) {
    private var consumed: Long = 0L

    fun header(name: String): String? = headers[name.lowercase()]

    /** 读取请求体（最多 [maxBytes] 字节，防止恶意超大 JSON）。 */
    fun bodyBytes(maxBytes: Int = 2 * 1024 * 1024): ByteArray {
        require(contentLength <= maxBytes) { "请求体过大" }
        if (contentLength <= 0) return ByteArray(0)
        val size = minOf(contentLength, maxBytes.toLong()).toInt()
        val buffer = ByteArray(size)
        var read = 0
        while (read < size) {
            val n = input.read(buffer, read, size - read)
            if (n < 0) break
            read += n
        }
        consumed += read
        return if (read == size) buffer else buffer.copyOf(read)
    }

    fun bodyText(): String = String(bodyBytes(), Charsets.UTF_8)

    /** 请求体的原始输入流，供大文件上传直接落盘；调用方负责按 [contentLength] 读取。 */
    fun bodyStream(): InputStream =
        object : InputStream() {
            override fun read(): Int {
                if (consumed >= contentLength) return -1
                val value = input.read()
                if (value >= 0) consumed++
                return value
            }

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (consumed >= contentLength) return -1
                val read =
                    input.read(
                        buffer,
                        offset,
                        minOf(length.toLong(), contentLength - consumed).toInt(),
                    )
                if (read > 0) consumed += read
                return read
            }
        }

    /**
     * 丢弃尚未读取的请求体。
     *
     * 上传提前返回（如目标已存在）前调用，避免客户端仍在写数据时连接被重置， 从而让它能正常读到 409 响应。
     */
    fun drainBody() {
        val buffer = ByteArray(512 * 1024)
        while (true) {
            val remaining = contentLength - consumed
            if (remaining <= 0) break
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read <= 0) break
            consumed += read
        }
    }
}

/** 服务端返回的响应。 */
data class HttpResponse(
    val status: Int,
    val body: ByteArray? = null,
    val contentType: String? = "application/json; charset=utf-8",
    val file: File? = null,
    val rangeStart: Long = 0,
    val extraHeaders: Map<String, String> = emptyMap(),
    val onBytes: (Int) -> Unit = {},
    val onComplete: (Boolean) -> Unit = {},
)

/**
 * HTTPS/1.1 服务端：固定长度请求体、持久连接、文件与 Range 输出。
 *
 * 使用每次安装的 TLS 身份；入站线程数有上限，不接受分块请求体。
 */
class HttpServerLite(
    private val identity: TransferIdentity,
    private val handler: (HttpRequest) -> HttpResponse,
) {

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var running = false

    private val executor =
        ThreadPoolExecutor(
            0,
            32,
            30L,
            TimeUnit.SECONDS,
            SynchronousQueue<Runnable>(),
            java.util.concurrent.ThreadFactory { runnable ->
                Thread(runnable, "spw-send-https").apply { isDaemon = true }
            },
        )

    private val dataTasks = ConcurrentHashMap<Socket, String>()

    fun cancelTask(task: String) {
        for ((socket, id) in dataTasks.entries) {
            if (id == task) runCatching { socket.close() }
        }
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

    private fun bindSocket(port: Int): ServerSocket =
        (identity.context.serverSocketFactory.createServerSocket() as SSLServerSocket).apply {
            enabledProtocols =
                supportedProtocols.filter { it == "TLSv1.3" || it == "TLSv1.2" }.toTypedArray()
            needClientAuth = false
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
        if (!executor.awaitTermination(15, TimeUnit.SECONDS)) {
            throw IOException("旧的传输请求尚未退出，不能重启服务")
        }
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
                s.soTimeout = 8_000
                (s as SSLSocket).startHandshake()
                val input = BufferedInputStream(s.getInputStream(), 512 * 1024)
                val output = BufferedOutputStream(s.getOutputStream(), 512 * 1024)
                while (running) {
                    s.soTimeout = 30_000
                    val line = readLine(input) ?: break
                    val parts = line.split(' ')
                    require(parts.size == 3 && parts[2] == "HTTP/1.1") { "非法 HTTP 请求" }
                    val headers = LinkedHashMap<String, String>()
                    var headerBytes = 0
                    while (true) {
                        val header = readLine(input) ?: throw IOException("请求头不完整")
                        if (header.isEmpty()) break
                        headerBytes += header.length
                        require(headerBytes <= 64 * 1024) { "请求头过大" }
                        val separator = header.indexOf(':')
                        require(separator > 0) { "非法请求头" }
                        val name = header.substring(0, separator).trim().lowercase()
                        require(name != "content-length" || !headers.containsKey(name)) { "重复长度" }
                        headers[name] = header.substring(separator + 1).trim()
                    }
                    require(!headers.containsKey("transfer-encoding")) { "需要固定 Content-Length" }
                    val target = parts[1]
                    val path = target.substringBefore('?')
                    val length =
                        headers["content-length"]?.let {
                            it.toLongOrNull() ?: throw IOException("非法长度")
                        } ?: 0L
                    require(length >= 0)
                    s.soTimeout = 45_000
                    val request =
                        HttpRequest(
                            parts[0].uppercase(),
                            path,
                            parseQuery(target.substringAfter('?', "")),
                            headers,
                            input,
                            length,
                            s.inetAddress.hostAddress,
                            s.port,
                        )
                    if (path == "/v2/file" || path == "/v2/upload") {
                        dataTasks[s] = request.header("X-MySend-Sync").orEmpty()
                    }
                    val response =
                        try {
                            handler(request)
                        } catch (e: Exception) {
                            HttpResponse(
                                500,
                                """{"ok":false,"code":"IO_ERROR","message":"${escape(e.message ?: "内部错误")}"}"""
                                    .toByteArray(),
                            )
                        }
                    var success = false
                    try {
                        // 只有完整消费请求体的连接才进入下一次解析。
                        request.drainBody()
                        writeResponse(output, response, request.method)
                        success = true
                    } finally {
                        try {
                            response.onComplete(success)
                        } finally {
                            dataTasks.remove(s)
                        }
                    }
                    if (headers["connection"].equals("close", ignoreCase = true)) break
                }
            }
        } catch (_: Exception) {
            // TLS 失败、超时或连接中断；请求作用域负责归还会话和文件槽位。
        } finally {
            dataTasks.remove(socket)
            activeSockets.remove(socket)
        }
    }

    private fun writeResponse(out: OutputStream, response: HttpResponse, method: String) {
        val head = StringBuilder(256)
        head
            .append("HTTP/1.1 ")
            .append(response.status)
            .append(' ')
            .append(REASONS[response.status] ?: "OK")
            .append("\r\n")
        head.append("Connection: keep-alive\r\n")

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
                copy(raf, out, response.onBytes)
            }
        } else {
            response.body?.let { out.write(it) }
        }
        out.flush()
    }

    private fun copy(source: RandomAccessFile, out: OutputStream, onBytes: (Int) -> Unit) {
        val buffer = ByteArray(512 * 1024)
        while (true) {
            val read = source.read(buffer)
            if (read <= 0) break
            out.write(buffer, 0, read)
            onBytes(read)
        }
    }

    private fun readLine(input: InputStream): String? {
        val buffer = ByteArrayOutputStream(64)
        var b = input.read()
        if (b < 0) return null
        while (b >= 0 && b != '\n'.code) {
            if (b != '\r'.code) {
                require(buffer.size() < 8192) { "请求行过长" }
                buffer.write(b)
            }
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

    private fun escape(value: String): String =
        buildString(value.length + 8) {
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
        private val REASONS =
            mapOf(
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
                507 to "Insufficient Storage",
            )
    }
}
