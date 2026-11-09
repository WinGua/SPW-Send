package com.wingua.spwsend.core

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 单个失败文件，[direction] 为 `send` / `recv`。 */
data class SyncFailure(
    val path: String,
    val reason: String,
    val direction: String = ""
)

/**
 * 一次同步任务日志。
 *
 * 本机视角同时记录发送与接收；`total` / `success` / `bytes` 继续保留，
 * 兼容旧日志与旧版协议字段。
 */
data class SyncLog(
    val task: String,
    val startedAt: Long,
    val finishedAt: Long,
    val total: Int,
    val success: Int,
    val failed: Int,
    val bytes: Long,
    val peerName: String = "",
    val sentCount: Int = 0,
    val sentBytes: Long = 0,
    val sentFiles: List<String> = emptyList(),
    val receivedCount: Int = 0,
    val receivedBytes: Long = 0,
    val receivedFiles: List<String> = emptyList(),
    val failures: List<SyncFailure> = emptyList()
) {
    fun asJson(): MutableMap<String, Any?> = Json.obj(
        "task" to task,
        "peer" to peerName,
        "startedAt" to startedAt,
        "finishedAt" to finishedAt,
        "total" to total,
        "success" to success,
        "failed" to failed,
        "bytes" to bytes,
        "sent" to Json.obj("c" to sentCount, "b" to sentBytes, "f" to sentFiles),
        "recv" to Json.obj("c" to receivedCount, "b" to receivedBytes, "f" to receivedFiles),
        "failures" to failures.map {
            Json.obj("p" to it.path, "reason" to it.reason, "d" to it.direction)
        }
    )

    fun summary(): String {
        val time = SimpleDateFormat("MM-dd HH:mm", Locale.ROOT).format(Date(finishedAt))
        val size = PathUtil.formatBytes(bytes)
        val peer = if (peerName.isEmpty()) "" else " · $peerName"
        val base = "$time  $task$peer  ↑$sentCount ↓$receivedCount · $size"
        return if (failed > 0) "$base · $failed 个失败" else base
    }
}

/** 本地同步日志（`~/.spw-send/logs.json`），保留 7 天。 */
class LogStore {

    private val dir: File = File(System.getProperty("user.home"), ".spw-send")
    private val file: File = File(dir, "logs.json")
    private val entries = ArrayList<SyncLog>()

    init {
        load()
    }

    @Synchronized
    fun add(log: SyncLog) {
        entries.add(0, log)
        prune()
        save()
    }

    @Synchronized
    fun recent(limit: Int = 30): List<SyncLog> {
        if (prune()) save()
        return entries.take(limit).toList()
    }

    @Synchronized
    fun clear() {
        entries.clear()
        save()
    }

    private fun load() {
        if (!file.exists()) return
        runCatching {
            val list = Json.parse(file.readText(Charsets.UTF_8)).asList()
            entries.clear()
            for (item in list) {
                val m = item.asMap()
                val sent = m["sent"].asMap()
                val recv = m["recv"].asMap()
                val directional = m["sent"] is Map<*, *> || m["recv"] is Map<*, *>
                // 旧日志来自单向推送时代，统一归入「已发送」。
                val sentCount = if (directional) sent.intValue("c") else m.intValue("success")
                val sentBytes = if (directional) sent.longValue("b") else m.longValue("bytes")
                entries.add(
                    SyncLog(
                        task = m.str("task"),
                        peerName = m.str("peer"),
                        startedAt = m.longValue("startedAt"),
                        finishedAt = m.longValue("finishedAt"),
                        total = m.intValue("total"),
                        success = m.intValue("success"),
                        failed = m.intValue("failed"),
                        bytes = m.longValue("bytes"),
                        sentCount = sentCount,
                        sentBytes = sentBytes,
                        sentFiles = sent["f"].asList().mapNotNull { it?.toString() }
                            .filter { it.isNotEmpty() },
                        receivedCount = if (directional) recv.intValue("c") else 0,
                        receivedBytes = if (directional) recv.longValue("b") else 0L,
                        receivedFiles = recv["f"].asList().mapNotNull { it?.toString() }
                            .filter { it.isNotEmpty() },
                        failures = m["failures"].asList().map { f ->
                            val fm = f.asMap()
                            SyncFailure(fm.str("p"), fm.str("reason"), fm.str("d"))
                        }
                    )
                )
            }
        }
        prune()
    }

    /** 移除超过保留期的日志，返回是否真的删除了内容。 */
    private fun prune(): Boolean {
        val cutoff = System.currentTimeMillis() - Constants.LOG_RETENTION_MS
        return entries.removeAll { it.finishedAt > 0 && it.finishedAt < cutoff }
    }

    private fun save() {
        try {
            if (!dir.exists()) dir.mkdirs()
            val temp = File(dir, file.name + ".tmp")
            temp.writeText(Json.stringify(entries.map { it.asJson() }), Charsets.UTF_8)
            try {
                Files.move(
                    temp.toPath(),
                    file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
                )
            } catch (_: Exception) {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (_: Exception) {
        }
    }
}
