package com.wingua.spwsend.ui

import androidx.compose.ui.graphics.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * 读取桌面壁纸的平均色，作为材质底色的取色来源。
 *
 * 只按缩略尺寸解码一次，不保留图片、不做模糊；壁纸未变化时直接复用缓存。
 * 读取或解码失败返回 null，由调用方回退到主题底色。
 */
internal object WallpaperPalette {

    private class Cache(
        val path: String?,
        val modified: Long,
        val color: Color?,
    )

    @Volatile
    private var cache: Cache? = null

    fun averageColor(): Color? {
        val file = resolveFile()
        val path = file?.absolutePath
        val modified = file?.lastModified() ?: 0L
        cache?.let {
            if (it.path == path && it.modified == modified) return it.color
        }
        val color = file?.let(::sample)
        cache = Cache(path, modified, color)
        return color
    }

    private fun resolveFile(): File? {
        // TranscodedWallpaper 由系统维护为 JPEG，优先读取，避免拉起 reg 进程。
        val path = transcodedWallpaper() ?: registryWallpaper() ?: return null
        return File(path).takeIf { it.isFile }
    }

    private fun sample(file: File): Color? = runCatching {
        val input = ImageIO.createImageInputStream(file) ?: return@runCatching null
        input.use { stream ->
            val readers = ImageIO.getImageReaders(stream)
            if (!readers.hasNext()) return@runCatching null
            val reader = readers.next()
            try {
                reader.setInput(stream)
                val param = reader.defaultReadParam
                val step = maxOf(1, maxOf(reader.getWidth(0), reader.getHeight(0)) / 64)
                param.setSourceSubsampling(step, step, 0, 0)
                averageColorOf(reader.read(0, param))
            } finally {
                reader.dispose()
            }
        }
    }.getOrNull()

    private fun averageColorOf(image: BufferedImage): Color? {
        var red = 0L
        var green = 0L
        var blue = 0L
        var count = 0L
        for (y in 0 until image.height step 2) {
            for (x in 0 until image.width) {
                val rgb = image.getRGB(x, y)
                if ((rgb ushr 24) == 0) continue
                red += (rgb shr 16) and 0xFF
                green += (rgb shr 8) and 0xFF
                blue += rgb and 0xFF
                count++
            }
        }
        if (count == 0L) return null
        return Color(
            (red / count).toInt() / 255f,
            (green / count).toInt() / 255f,
            (blue / count).toInt() / 255f,
        )
    }

    private fun registryWallpaper(): String? = runCatching {
        val process = Runtime.getRuntime().exec(
            arrayOf("reg", "query", "HKCU\\Control Panel\\Desktop", "/v", "WallPaper")
        )
        val output = process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor()
        val line = output.lineSequence().firstOrNull { it.contains("REG_SZ") }
        line?.substringAfter("REG_SZ")?.trim()?.takeIf { it.isNotEmpty() }
    }.getOrNull()

    private fun transcodedWallpaper(): String? {
        val appData = System.getenv("APPDATA") ?: return null
        val file = File(appData, "Microsoft\\Windows\\Themes\\TranscodedWallpaper")
        return if (file.isFile) file.absolutePath else null
    }
}
