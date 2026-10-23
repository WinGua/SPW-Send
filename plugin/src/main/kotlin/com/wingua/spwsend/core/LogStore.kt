package com.wingua.spwsend.core

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 单个失败文件。 */
data class SyncFailure(val path: String, val reason: String)

/** 一次同步任务日志，字段与协议 `/v2/log` 一致。 */
data class SyncLog(
    val task: String,
    val startedAt: Long,
    val finishedAt: Long,
    val total: Int,
    val success: Int,
    val failed: Int,
    val bytes: Long,
    val failures: List<SyncFailure> = emptyList()
) {
    fun asJson(): MutableMap<String, Any?> = Json.obj(
        "task" to task,
        "startedAt" to startedAt,
        "finishedAt" to finishedAt,
        "total" to total,
        "success" to success,
        "failed" to failed,
        "bytes" to bytes,
        "failures" to failures.map { Json.obj("p" to it.path, "reason" to it.reason) }
    )

    fun summary(): String {
        val time = SimpleDateFormat("MM-dd HH:mm", Locale.ROOT).format(Date(finishedAt))
        val size = PathUtil.formatBytes(bytes)
        return if (failed > 0) {
            "$time  $task  ${success}/${total} 成功 · $size · ${failed} 个失败"
        } else {
            "$time  $task  $success 个文件 · $size"
        }
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
        prune()
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
                entries.add(
                    SyncLog(
                        task = m.str("task"),
                        startedAt = m.longValue("startedAt"),
                        finishedAt = m.longValue("finishedAt"),
                        total = m.intValue("total"),
                        success = m.intValue("success"),
                        failed = m.intValue("failed"),
                        bytes = m.longValue("bytes"),
                        failures = m["failures"].asList().map { f ->
                            val fm = f.asMap()
                            SyncFailure(fm.str("p"), fm.str("reason"))
                        }
                    )
                )
            }
        }
        prune()
    }

    private fun prune() {
        val cutoff = System.currentTimeMillis() - Constants.LOG_RETENTION_MS
        entries.removeAll { it.finishedAt > 0 && it.finishedAt < cutoff }
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
