package com.wingua.spwsend.core

import java.io.File
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.ArrayDeque
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 本地诊断日志。
 *
 * 正常运行只在内存保留最近操作，不写磁盘；
 * 未捕获异常发生时写一份完整报告，供用户在配置页导出。
 */
object Diagnostics {

    private const val MAX_EVENTS = 100
    private const val MAX_CRASH_FILES = 5
    private const val MAX_SESSION_CRASHES = 5
    private const val MAX_EXPORT_FILES = 3
    private const val MAX_REPORT_CHARS = 900_000
    private const val FEEDBACK_URL = "https://github.com/WinGua/SPW-Send/issues/new"

    private val baseDir = File(System.getProperty("user.home"), ".spw-send")
    private val diagnosticsDir = File(baseDir, "diagnostics")
    private val exportDir = File(diagnosticsDir, "export")
    private val sessionFile = File(diagnosticsDir, "session.json")

    private val events = ArrayDeque<String>()
    private val seenSignatures = HashSet<String>()
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS", Locale.ROOT)
    private val stampFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT)
    private val fullFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT)

    private var pluginVersion = "unknown"
    private var hostVersion = "unknown"
    private var sessionStartedAt = 0L
    private var crashCount = 0
    private var installed = false
    private var capturing = false

    @Volatile
    private var previousHandler: Thread.UncaughtExceptionHandler? = null

    /** 由面板控制器提供当前运行状态，崩溃时写入报告。 */
    @Volatile
    var stateProvider: (() -> String)? = null

    /** 由面板控制器提供映射等配置摘要，崩溃时写入报告。 */
    @Volatile
    var configProvider: (() -> String)? = null

    private val handler = Thread.UncaughtExceptionHandler { thread, throwable ->
        capture(throwable, thread.name, "uncaught")
        val previous = previousHandler
        try {
            if (previous != null) {
                previous.uncaughtException(thread, throwable)
            } else {
                throwable.printStackTrace()
            }
        } catch (_: Throwable) {
        }
    }

    /** 安装全局异常捕获，返回上次异常退出的提示（没有则为 null）。 */
    @Synchronized
    fun install(pluginVersion: String, hostVersion: String): String? {
        if (installed) return null
        this.pluginVersion = pluginVersion.ifEmpty { "unknown" }
        this.hostVersion = hostVersion.ifEmpty { "unknown" }
        sessionStartedAt = System.currentTimeMillis()
        events.clear()
        seenSignatures.clear()
        crashCount = 0
        ensureDirs()
        val notice = startupNotice()
        val current = Thread.getDefaultUncaughtExceptionHandler()
        if (current !== handler) {
            previousHandler = current
            Thread.setDefaultUncaughtExceptionHandler(handler)
        }
        installed = true
        writeSession(clean = false)
        return notice
    }

    @Synchronized
    fun uninstall() {
        if (!installed) return
        installed = false
        writeSession(clean = true)
        if (Thread.getDefaultUncaughtExceptionHandler() === handler) {
            Thread.setDefaultUncaughtExceptionHandler(previousHandler)
        }
        previousHandler = null
    }

    /** 记录一条高层操作，只保存在内存中。 */
    @Synchronized
    fun record(tag: String, message: String) {
        val line = "${LocalTime.now().format(timeFormatter)}  [$tag] $message"
        if (events.size >= MAX_EVENTS) events.removeFirst()
        events.addLast(line)
    }

    /** 捕获并落盘一次异常；重复堆栈与单会话上限会跳过写文件。 */
    @Synchronized
    fun capture(throwable: Throwable, threadName: String, source: String) {
        if (capturing) return
        capturing = true
        try {
            val signature = signatureOf(throwable)
            record("crash", "$source: ${throwable.javaClass.name}: ${throwable.message.orEmpty()}")
            val canWrite = crashCount < MAX_SESSION_CRASHES && seenSignatures.add(signature)
            if (canWrite) {
                crashCount++
                ensureDirs()
                val file = newCrashFile()
                file.writeText(buildReport(throwable, threadName, source), Charsets.UTF_8)
                pruneCrashFiles()
            }
        } catch (_: Throwable) {
        } finally {
            capturing = false
        }
    }

    /** 诊断目录；调用方负责用系统文件管理器打开。 */
    @Synchronized
    fun folder(): File {
        ensureDirs()
        return diagnosticsDir
    }

    @Synchronized
    fun hasCrashFiles(): Boolean = crashFiles().isNotEmpty()

    /** 生成包含完整日志、当前状态和反馈模板的 zip。 */
    @Synchronized
    fun exportPackage(): File {
        ensureDirs()
        val stamp = LocalDateTime.now().format(stampFormatter)
        val zip = File(exportDir, "spw-send-diagnostics-$stamp.zip")
        ZipOutputStream(zip.outputStream().buffered()).use { out ->
            addEntry(out, "manifest.txt", buildManifest())
            addEntry(out, "state.txt", buildStateText())
            val sessionText = runCatching {
                if (sessionFile.isFile) sessionFile.readText(Charsets.UTF_8) else "{}"
            }.getOrDefault("{}")
            addEntry(out, "session.json", sessionText)
            crashFiles().take(MAX_CRASH_FILES).forEach { crash ->
                val text = runCatching { crash.readText(Charsets.UTF_8) }.getOrDefault("")
                addEntry(out, crash.name, text)
            }
        }
        pruneExports()
        return zip
    }

    /** 预填反馈模板的 GitHub 新建 issue 地址。 */
    fun feedbackUrl(): String {
        val body = feedbackTemplate()
        return "$FEEDBACK_URL?title=${encode("SPW-Send 崩溃反馈")}&body=${encode(body)}"
    }

    private fun startupNotice(): String? {
        val previous = readSession() ?: return null
        if (previous.clean) return null
        val newest = crashFiles().firstOrNull() ?: return null
        if (newest.lastModified() < previous.startedAt) return null
        return "上次运行发生异常，可在插件配置页「导出诊断包」后反馈"
    }

    private fun writeSession(clean: Boolean) {
        runCatching {
            ensureDirs()
            sessionFile.writeText(
                Json.stringify(
                    Json.obj(
                        "pluginVersion" to pluginVersion,
                        "hostVersion" to hostVersion,
                        "startedAt" to sessionStartedAt,
                        "clean" to clean,
                        "updatedAt" to System.currentTimeMillis()
                    )
                ),
                Charsets.UTF_8
            )
        }
    }

    private data class SessionInfo(val startedAt: Long, val clean: Boolean)

    private fun readSession(): SessionInfo? {
        if (!sessionFile.isFile) return null
        val data = runCatching { Json.parse(sessionFile.readText(Charsets.UTF_8)).asMap() }
            .getOrNull() ?: return null
        return SessionInfo(
            startedAt = data.longValue("startedAt"),
            clean = data["clean"] as? Boolean ?: true
        )
    }

    private fun buildReport(throwable: Throwable, threadName: String, source: String): String {
        val sb = StringBuilder(8192)
        sb.appendLine("=== SPW-Send 诊断报告 ===")
        sb.appendLine("分享前请检查本文件是否包含个人路径。")
        sb.appendLine()
        sb.appendLine("时间: ${LocalDateTime.now().format(fullFormatter)} ${ZoneId.systemDefault()}")
        sb.appendLine("插件版本: $pluginVersion")
        sb.appendLine("宿主版本: $hostVersion")
        sb.appendLine("系统: ${System.getProperty("os.name").orEmpty()} ${System.getProperty("os.version").orEmpty()} ${System.getProperty("os.arch").orEmpty()}")
        sb.appendLine("Java: ${System.getProperty("java.version").orEmpty()} (${System.getProperty("java.vendor").orEmpty()})")
        sb.appendLine("语言: ${System.getProperty("user.language").orEmpty()}_${System.getProperty("user.country").orEmpty()}")
        sb.appendLine("线程: $threadName")
        sb.appendLine("来源: $source")
        sb.appendLine("与插件相关: ${if (isPluginRelated(throwable)) "是" else "否"}")
        sb.appendLine()
        sb.appendLine("--- 异常 ---")
        appendThrowable(sb, throwable, "")
        sb.appendLine()
        sb.appendLine("--- 最近操作（最后 ${events.size} 条）---")
        if (events.isEmpty()) {
            sb.appendLine("暂无记录")
        } else {
            events.forEach { sb.appendLine(it) }
        }
        sb.appendLine()
        sb.appendLine("--- 状态快照 ---")
        sb.appendLine(runCatching { stateProvider?.invoke() }.getOrNull() ?: "面板尚未初始化")
        sb.appendLine()
        sb.appendLine("--- 配置摘要 ---")
        sb.appendLine(runCatching { configProvider?.invoke() }.getOrNull() ?: "无")
        sb.appendLine()
        sb.appendLine("--- 线程转储 ---")
        sb.appendLine(buildThreadDump())
        if (sb.length > MAX_REPORT_CHARS) {
            sb.setLength(MAX_REPORT_CHARS)
            sb.appendLine()
            sb.appendLine("... 报告过长，已截断 ...")
        }
        return sb.toString()
    }

    private fun appendThrowable(sb: StringBuilder, throwable: Throwable, indent: String) {
        sb.appendLine("$indent${throwable.javaClass.name}: ${throwable.message.orEmpty()}")
        for (frame in throwable.stackTrace) {
            sb.appendLine("$indent    at $frame")
        }
        var cause = throwable.cause
        var depth = 0
        while (cause != null && cause !== throwable && depth < 8) {
            sb.appendLine("${indent}Caused by: ${cause.javaClass.name}: ${cause.message.orEmpty()}")
            for (frame in cause.stackTrace) {
                sb.appendLine("$indent    at $frame")
            }
            cause = cause.cause
            depth++
        }
    }

    private fun buildThreadDump(): String {
        val traces = runCatching { Thread.getAllStackTraces() }.getOrNull()
            ?: return "无法读取线程转储"
        val sb = StringBuilder(4096)
        var index = 0
        for ((thread, stack) in traces) {
            if (index >= 200) {
                sb.appendLine("... 其余 ${traces.size - index} 个线程已省略")
                break
            }
            val daemon = if (thread.isDaemon) " daemon" else ""
            sb.appendLine("\"${thread.name}\" #${thread.threadId()} ${thread.state}$daemon")
            for (frame in stack.take(60)) {
                sb.appendLine("    at $frame")
            }
            sb.appendLine()
            index++
        }
        return sb.toString()
    }

    private fun isPluginRelated(throwable: Throwable): Boolean =
        throwable.stackTrace.any { it.className.startsWith("com.wingua.spwsend") }

    private fun signatureOf(throwable: Throwable): String {
        val message = throwable.message.orEmpty().take(200)
        val frames = throwable.stackTrace.take(4).joinToString("|") {
            "${it.className}.${it.methodName}:${it.lineNumber}"
        }
        return "${throwable.javaClass.name}:$message|$frames"
    }

    private fun crashFiles(): List<File> =
        diagnosticsDir.listFiles { file ->
            file.isFile && file.name.startsWith("crash-") && file.name.endsWith(".log")
        }?.sortedByDescending { it.lastModified() }.orEmpty()

    private fun newCrashFile(): File {
        val stamp = LocalDateTime.now().format(stampFormatter)
        var file = File(diagnosticsDir, "crash-$stamp.log")
        var index = 1
        while (file.exists()) {
            file = File(diagnosticsDir, "crash-$stamp-$index.log")
            index++
        }
        return file
    }

    private fun pruneCrashFiles() {
        crashFiles().drop(MAX_CRASH_FILES).forEach { runCatching { it.delete() } }
    }

    private fun pruneExports() {
        exportDir.listFiles { file -> file.isFile && file.name.endsWith(".zip") }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(MAX_EXPORT_FILES)
            ?.forEach { runCatching { it.delete() } }
    }

    private fun ensureDirs() {
        if (!diagnosticsDir.exists()) diagnosticsDir.mkdirs()
        if (!exportDir.exists()) exportDir.mkdirs()
    }

    private fun buildManifest(): String = buildString {
        appendLine("SPW-Send 诊断包")
        appendLine("导出时间: ${LocalDateTime.now().format(fullFormatter)} ${ZoneId.systemDefault()}")
        appendLine("插件版本: $pluginVersion")
        appendLine("宿主版本: $hostVersion")
        appendLine("系统: ${System.getProperty("os.name").orEmpty()} ${System.getProperty("os.version").orEmpty()} ${System.getProperty("os.arch").orEmpty()}")
        appendLine("Java: ${System.getProperty("java.version").orEmpty()}")
    }

    private fun buildStateText(): String = buildString {
        appendLine("=== 当前状态 ===")
        appendLine(runCatching { stateProvider?.invoke() }.getOrNull() ?: "面板尚未初始化")
        appendLine()
        appendLine("=== 配置摘要 ===")
        appendLine(runCatching { configProvider?.invoke() }.getOrNull() ?: "无")
        appendLine()
        appendLine("=== 线程转储 ===")
        appendLine(buildThreadDump())
    }

    private fun feedbackTemplate(): String = """
        # SPW-Send 崩溃反馈

        插件版本: $pluginVersion
        宿主版本: $hostVersion

        ## 1. 崩溃前你在做什么？
        <!-- 例如：点击同步 / 正在接收文件 / 打开面板后空闲 -->

        ## 2. 是否能复现？
        <!-- 每次 / 偶尔 / 只出现过一次 -->

        ## 3. 还安装了哪些创意工坊模组？
        <!-- 没有就写“无” -->

        ## 4. 其他补充
        <!-- 例如：同步文件数量、是否使用中文路径、手机是否同时在推文件 -->

        ---

        请把导出的诊断包拖入本页附件区。
        分享前请检查 crash 日志中是否包含个人路径。
    """.trimIndent()

    private fun addEntry(out: ZipOutputStream, name: String, text: String) {
        out.putNextEntry(ZipEntry(name))
        out.write(text.toByteArray(Charsets.UTF_8))
        out.closeEntry()
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")
}
