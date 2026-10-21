package com.wingua.spwsend.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.decodeToImageBitmap
import java.io.File

/**
 * 读取 Windows 当前桌面壁纸，作为云母 / 亚克力的近似纹理。
 *
 * 先查注册表，再回退到系统的 TranscodedWallpaper 缓存；
 * 读取或解码失败返回 null，由调用方回退到渐变背景。
 */
internal object Wallpaper {

    @Volatile
    private var cached: ImageBitmap? = null

    @Volatile
    private var loaded = false

    fun load(): ImageBitmap? {
        if (loaded) return cached
        val bitmap = runCatching {
            val path = registryWallpaper() ?: transcodedWallpaper()
            if (path == null || !File(path).isFile) {
                null
            } else {
                File(path).readBytes().decodeToImageBitmap()
            }
        }.getOrNull()
        cached = bitmap
        loaded = true
        return bitmap
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
