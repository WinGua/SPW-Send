package com.wingua.spwsend.sync

import com.wingua.spwsend.core.Constants
import com.wingua.spwsend.core.Json
import com.wingua.spwsend.core.asMap
import com.wingua.spwsend.core.longValue
import com.wingua.spwsend.core.str
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale

/** 分片元数据，字段与 MySend 的 `.mysend-part` 元数据保持一致。 */
data class PartMeta(
    val path: String,
    val size: Long,
    val mtime: Long
)

/**
 * `.mysend-part` 分片管理。
 *
 * 分片路径：`<映射根>/.mysend-part/<sha256(相对路径)>.part`
 * 元数据：同目录 `<sha256(相对路径)>.meta`
 *
 * 分片只在「偏移 + 最终字节数」都校验通过后才原子改名到目标路径，
 * 因此中断不会留下半截正式文件。
 */
object PartStore {

    fun keyFor(relativePath: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(relativePath.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") {
            String.format(Locale.ROOT, "%02x", it.toInt() and 0xff)
        }
    }

    fun partFile(root: File, relativePath: String): File =
        File(File(root, Constants.PART_DIR), keyFor(relativePath) + ".part")

    fun metaFile(root: File, relativePath: String): File =
        File(File(root, Constants.PART_DIR), keyFor(relativePath) + ".meta")

    fun readMeta(root: File, relativePath: String): PartMeta? {
        val file = metaFile(root, relativePath)
        if (!file.isFile) return null
        return runCatching {
            val map = Json.parse(file.readText(Charsets.UTF_8)).asMap()
            PartMeta(
                path = map.str("p"),
                size = map.longValue("size"),
                mtime = map.longValue("mtime")
            )
        }.getOrNull()
    }

    fun writeMeta(root: File, relativePath: String, meta: PartMeta) {
        val file = metaFile(root, relativePath)
        file.parentFile?.mkdirs()
        file.writeText(
            Json.stringify(
                Json.obj(
                    "p" to meta.path,
                    "size" to meta.size,
                    "mtime" to meta.mtime
                )
            ),
            Charsets.UTF_8
        )
    }

    fun discard(root: File, relativePath: String) {
        runCatching { partFile(root, relativePath).delete() }
        runCatching { metaFile(root, relativePath).delete() }
    }

    /**
     * 返回可续传的字节偏移。
     *
     * 分片缺失、元数据缺失、大小/修改时间不一致时清理分片并返回 0，
     * 避免把旧分片接到内容已变化的新文件后面。
     */
    fun currentOffset(root: File, relativePath: String, size: Long, mtime: Long): Long {
        val part = partFile(root, relativePath)
        val meta = readMeta(root, relativePath)
        if (meta == null) {
            runCatching { part.delete() }
            return 0L
        }
        if (meta.path != relativePath || meta.size != size || meta.mtime != mtime) {
            discard(root, relativePath)
            return 0L
        }
        if (!part.isFile) return 0L
        val length = part.length()
        if (length > size) {
            discard(root, relativePath)
            return 0L
        }
        return length
    }

    /**
     * 把校验完成的分片改名到目标路径。
     *
     * 目标已存在时返回 false，绝不覆盖；原子改名不可用时退化为普通移动。
     */
    fun promote(part: File, target: File): Boolean {
        if (target.exists()) return false
        return try {
            Files.move(part.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            true
        } catch (_: AtomicMoveNotSupportedException) {
            try {
                Files.move(part.toPath(), target.toPath())
                true
            } catch (e: FileSystemException) {
                if (target.exists()) false else throw e
            }
        } catch (e: FileSystemException) {
            if (target.exists()) false else throw e
        }
    }

    /** 服务启动时清理超过保留期的分片与元数据。 */
    fun cleanup(root: File) {
        val dir = File(root, Constants.PART_DIR)
        if (!dir.isDirectory) return
        val cutoff = System.currentTimeMillis() - Constants.PART_RETENTION_MS
        val files = dir.listFiles() ?: return
        for (file in files) {
            if (!file.isFile) continue
            val name = file.name.lowercase(Locale.ROOT)
            if (!name.endsWith(".part") && !name.endsWith(".meta")) continue
            val modified = file.lastModified()
            if (modified > 0 && modified < cutoff) {
                runCatching { file.delete() }
            }
        }
    }
}
