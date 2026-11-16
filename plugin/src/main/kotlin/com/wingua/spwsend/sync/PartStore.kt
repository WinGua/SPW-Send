package com.wingua.spwsend.sync

import com.wingua.spwsend.core.Constants
import com.wingua.spwsend.core.Json
import com.wingua.spwsend.core.asMap
import com.wingua.spwsend.core.longValue
import com.wingua.spwsend.core.str
import java.io.File
import java.security.MessageDigest
import java.util.Locale

/** 分片元数据，字段与 MySend 的 `.mysend-part` 元数据保持一致。 */
data class PartMeta(
    val path: String,
    val size: Long,
    val mtime: Long,
)

/**
 * `.mysend-part` 分片管理。
 *
 * 分片路径：`<映射根>/.mysend-part/<sha256(相对路径)>.part` 元数据：同目录 `<sha256(相对路径)>.meta`
 *
 * 分片只在「偏移 + 最终字节数」都校验通过后才原子改名到目标路径， 因此中断不会留下半截正式文件。
 */
object PartStore {
    fun keyFor(relativePath: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(relativePath.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 255) }

    private fun relative(path: String, extension: String) =
        "${Constants.PART_DIR}/${keyFor(path)}.$extension"

    fun partFile(root: File, relativePath: String) = File(root, relative(relativePath, "part"))

    fun metaFile(root: File, relativePath: String) = File(root, relative(relativePath, "meta"))

    private fun <T> withFile(
        root: File,
        path: String,
        create: Boolean = false,
        action: (BoundDirectory, String) -> T,
    ): T =
        FileScope(root).use { scope ->
            val (dir, name) = scope.parent(path, create)
            action(dir, name)
        }

    fun readMeta(root: File, relativePath: String): PartMeta? = runCatching {
        withFile(root, relative(relativePath, "meta")) { dir, name ->
            val entry = dir.stat(name) ?: return@withFile null
            if (!entry.regular || entry.size > 65536) throw java.io.IOException("分片元数据无效")
            val raw = dir.read(name).use { it.readNBytes(65537) }
            if (raw.size > 65536) throw java.io.IOException("分片元数据过大")
            val map = Json.parse(String(raw, Charsets.UTF_8)).asMap()
            PartMeta(map.str("p"), map.longValue("size"), map.longValue("mtime"))
        }
    }
        .getOrNull()

    @Synchronized
    fun writeMeta(root: File, relativePath: String, meta: PartMeta) {
        val bytes =
            Json.stringify(Json.obj("p" to meta.path, "size" to meta.size, "mtime" to meta.mtime))
                .toByteArray(Charsets.UTF_8)
        withFile(root, relative(relativePath, "meta"), true) { dir, name ->
            dir.write(name, false).use { it.write(bytes) }
        }
    }

    /** Hold the directory authority until the payload stream is closed. */
    fun openPart(root: File, relativePath: String, append: Boolean): java.io.OutputStream {
        val scope = FileScope(root)
        try {
            val (dir, name) = scope.parent(relative(relativePath, "part"), true)
            val output = dir.write(name, append)
            return object : java.io.OutputStream() {
                private var closed = false

                override fun write(value: Int) = output.write(value)

                override fun write(bytes: ByteArray, off: Int, len: Int) =
                    output.write(bytes, off, len)

                override fun flush() = output.flush()

                override fun close() {
                    if (!closed) {
                        closed = true
                        try {
                            output.close()
                        } finally {
                            scope.close()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            scope.close()
            throw e
        }
    }

    fun partLength(root: File, relativePath: String): Long =
        try {
            withFile(root, relative(relativePath, "part")) { dir, name ->
                val entry = dir.stat(name) ?: return@withFile 0L
                if (!entry.regular) throw java.io.IOException("分片路径不是普通文件")
                entry.size
            }
        } catch (_: java.nio.file.NoSuchFileException) {
            0L
        }

    fun setModified(root: File, relativePath: String, millis: Long) =
        withFile(root, relative(relativePath, "part")) { dir, name -> dir.modified(name, millis) }

    @Synchronized
    fun discard(root: File, relativePath: String) {
        for (extension in listOf("part", "meta")) try {
            withFile(root, relative(relativePath, extension)) { dir, name -> dir.delete(name) }
        } catch (_: java.nio.file.NoSuchFileException) {}
        removeEmptyDirectory(root)
    }

    private fun removeEmptyDirectory(root: File) {
        // Best-effort removal; active files or directory handles keep the cache in place.
        runCatching { withFile(root, Constants.PART_DIR) { dir, name -> dir.delete(name, true) } }
    }

    fun currentOffset(root: File, relativePath: String, size: Long, mtime: Long): Long {
        val meta = readMeta(root, relativePath)
        if (meta == null) {
            if (partLength(root, relativePath) > 0) discard(root, relativePath)
            return 0L
        }
        if (meta.path != relativePath || meta.size != size || meta.mtime != mtime) {
            discard(root, relativePath)
            return 0L
        }
        val length = partLength(root, relativePath)
        if (length > size) {
            discard(root, relativePath)
            return 0L
        }
        return length
    }

    /** Atomic publication refuses an existing destination on every supported platform. */
    fun promote(part: File, target: File): Boolean {
        val root =
            if (part.parentFile.name == Constants.PART_DIR) part.parentFile.parentFile
            else part.parentFile
        val base = root.toPath().toAbsolutePath().normalize()
        val from = base.relativize(part.toPath().toAbsolutePath().normalize()).toString()
        val targetPath = target.toPath().toAbsolutePath().normalize()
        // PathUtil returns a canonical destination; the configured root may use an OS alias.
        val targetBase = if (targetPath.startsWith(base)) base else root.toPath().toRealPath()
        val to = targetBase.relativize(targetPath).toString()
        return FileScope(root).use { scope ->
            val (source, name) = scope.parent(from)
            val (destination, newName) = scope.parent(to, true)
            source.publish(name, destination, newName)
        }
    }

    @Synchronized
    fun cleanup(root: File) {
        val cutoff = System.currentTimeMillis() - Constants.PART_RETENTION_MS
        try {
            FileScope(root).use { scope ->
                val directory = scope.directory(Constants.PART_DIR)
                for (entry in directory.entries()) {
                    val name = entry.name.lowercase(Locale.ROOT)
                    if (
                        entry.regular &&
                            (name.endsWith(".part") || name.endsWith(".meta")) &&
                            entry.modified > 0 &&
                            entry.modified < cutoff
                    )
                        runCatching { directory.delete(entry.name) }
                }
            }
        } catch (_: java.nio.file.NoSuchFileException) {
            return
        }
        removeEmptyDirectory(root)
    }
}
