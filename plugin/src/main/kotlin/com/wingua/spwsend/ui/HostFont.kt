package com.wingua.spwsend.ui

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.Font
import java.nio.file.Files
import java.nio.file.Path

/**
 * 读取椒盐音乐的字体设置。
 *
 * 内置 MiSans 直接从宿主类加载器读取，自定义字体按配置路径载入，
 * 不向插件包内复制字体文件。
 */
object HostFont {

    private const val MISANS_RESOURCE =
        "composeResources/voxzen.composeapp.generated.resources/font/misans.ttf"

    @Volatile
    private var cacheKey: String? = null

    @Volatile
    private var cacheFamily: FontFamily = FontFamily.Default

    fun load(strategy: String?, customPath: String?): FontFamily {
        val key = "${strategy.orEmpty()}|${customPath.orEmpty()}"
        if (key == cacheKey) return cacheFamily
        val family = runCatching { resolve(strategy, customPath) }
            .getOrDefault(FontFamily.Default)
        cacheKey = key
        cacheFamily = family
        return family
    }

    private fun resolve(strategy: String?, customPath: String?): FontFamily = when (strategy) {
        "MiSansRegular" -> {
            val bytes = HostAppearanceReader.hostClassLoader()
                ?.getResourceAsStream(MISANS_RESOURCE)
                ?.use { it.readBytes() }
                ?: return FontFamily.Default
            FontFamily(Font(identity = "spw-send:misans", data = bytes))
        }

        "Custom" -> {
            val path = customPath?.takeIf { it.isNotBlank() }
                ?: return FontFamily.Default
            java.awt.Font.createFont(java.awt.Font.TRUETYPE_FONT, Path.of(path).toFile())
            FontFamily(
                Font(
                    identity = "spw-send:custom:$path",
                    data = Files.readAllBytes(Path.of(path)),
                )
            )
        }

        else -> FontFamily.Default
    }
}
