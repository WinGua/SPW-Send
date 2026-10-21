package com.wingua.spwsend.core

import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Locale

/** 源目录中的文件条目。 */
data class LocalFile(
    val relativePath: String,
    val size: Long,
    val mtimeMs: Long,
    val file: File
)

/** 路径、过滤与摘要相关工具。 */
object PathUtil {

    /** 统一分隔符为 `/`，去掉开头的 `./`。 */
    fun normalizeRelative(path: String): String =
        path.replace('\\', '/').trimStart('/').removePrefix("./")

    /** 逐路径段判断隐藏文件与系统垃圾文件。 */
    fun isIgnored(relativePath: String): Boolean {
        for (segment in relativePath.split('/')) {
            if (segment.isEmpty()) continue
            if (segment.startsWith(".")) return true
            if (segment.startsWith("~\u0024")) return true
            val lower = segment.lowercase()
            if (lower == "desktop.ini" || lower == "thumbs.db" || lower == "ehthumbs.db") {
                return true
            }
        }
        return false
    }

    /** 校验协议相对路径：非空、相对、无 `..` / 空段 / `:` / 分片目录。 */
    fun isValidRelative(relativePath: String): Boolean {
        if (relativePath.isEmpty()) return false
        if (relativePath.startsWith("/") || relativePath.contains('\\')) return false
        for (segment in relativePath.split('/')) {
            if (segment.isEmpty() || segment == "." || segment == "..") return false
            if (segment.contains(':')) return false
            if (segment == Constants.PART_DIR) return false
        }
        return true
    }

    /** 在 [root] 内安全解析相对路径，越界或符号链接逃逸返回 null。 */
    fun resolveSafely(root: File, relativePath: String): File? {
        if (!isValidRelative(relativePath)) return null
        return try {
            val rootCanonical = root.canonicalFile
            val target = File(rootCanonical, relativePath.replace('/', File.separatorChar))
            val targetCanonical = target.canonicalFile
            val rootPath = rootCanonical.path
            val prefix = if (rootPath.endsWith(File.separator)) rootPath else rootPath + File.separator
            if (targetCanonical.path == rootPath || targetCanonical.path.startsWith(prefix)) {
                targetCanonical
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    /** 递归扫描源目录，返回过滤后的文件列表（按相对路径排序）。 */
    fun scan(root: File): List<LocalFile> {
        if (!root.isDirectory) return emptyList()
        val result = ArrayList<LocalFile>()
        val rootFile = root.absoluteFile
        rootFile.walkTopDown()
            .onEnter { dir ->
                (dir == rootFile || !Files.isSymbolicLink(dir.toPath())) &&
                    !(dir != rootFile && (dir.name.startsWith(".") ||
                        dir.name.equals("desktop.ini", true)))
            }
            .filter { it.isFile && !Files.isSymbolicLink(it.toPath()) }
            .forEach { f ->
                val rel = normalizeRelative(
                    f.absoluteFile.toRelativeString(rootFile).replace('\\', '/')
                )
                if (rel.isEmpty() || isIgnored(rel)) return@forEach
                result.add(
                    LocalFile(
                        relativePath = rel,
                        size = f.length(),
                        mtimeMs = f.lastModified(),
                        file = f
                    )
                )
            }
        result.sortBy { it.relativePath }
        return result
    }

    /** 计算文件 SHA-256 小写十六进制摘要。 */
    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        RandomAccessFile(file, "r").use { raf ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = raf.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { String.format(Locale.ROOT, "%02x", it) }
    }

    /** 人类可读的字节数。 */
    fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format(Locale.ROOT, "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(Locale.ROOT, "%.1f MB", mb)
        return String.format(Locale.ROOT, "%.2f GB", mb / 1024.0)
    }
}
