package com.wingua.spwsend.ui

import androidx.compose.ui.graphics.Color
import com.moriafly.salt.ui.SaltColors

/** 由宿主窗口绘制 DWM 背景的样式。 */
internal val MATERIAL_WINDOW_STYLES = setOf(
    "WindowsAcrylic",
    "WindowsMica",
    "WindowsMicaAlt",
)

/**
 * 按宿主窗口样式还原配色。
 *
 * 材质样式取自椒盐音乐 1.19.0 的经典 / 亚克力 / 云母 / 云母 Alt 页面取样值：
 * 材质模式下页面透明、卡片半透明，让窗口背景直接参与合成；
 * 无法接管内容层时退回与宿主经典主题一致的实色页面。
 */
internal fun hostPalette(windowStyle: String?, dark: Boolean, materialActive: Boolean): SaltColors {
    val base = if (dark) SaltColors.defaultDark() else SaltColors.defaultLight()
    if (!materialActive) {
        return base.copy(
            background = if (dark) Color(0xFF232323) else Color(0xFFF8F8F8),
            subBackground = if (dark) Color(0xFF2A2A2A) else Color(0xFFFCFCFC),
            popup = if (dark) Color(0xFF2A2A2A) else Color(0xFFFCFCFC),
            text = if (dark) Color(0xFFEDEDEE) else Color(0xFF25272B),
            subText = if (dark) Color(0xFFA0A3AB) else Color(0xFF777B82),
            stroke = if (dark) Color(0xFF37393E) else Color(0xFFE9EAED)
        )
    }
    if (dark) {
        return base.copy(
            background = Color.Transparent,
            subBackground = Color(0xFF7C7C7C).copy(alpha = 0.34f),
            popup = Color(0xFF2A2A2A),
            text = Color(0xFFEBEEF1),
            subText = Color(0xFFCCD0D5).copy(alpha = 0.91f),
            stroke = Color(0xFF9B9BA1).copy(alpha = 1f / 3f)
        )
    }
    if (windowStyle == "WindowsAcrylic") {
        return base.copy(
            background = Color.Transparent,
            subBackground = Color.White.copy(alpha = 0.16f),
            popup = Color(0xFFFCFCFC),
            text = Color(0xFF1E1715),
            subText = Color.Black.copy(alpha = 0.56f),
            stroke = Color(0xFF808080).copy(alpha = 0.28f)
        )
    }
    return base.copy(
        background = Color.Transparent,
        subBackground = Color(0xFFFCFCFC).copy(alpha = 0.72f),
        popup = Color(0xFFFCFCFC),
        text = Color(0xFF25272B),
        subText = Color(0xFF777B82),
        stroke = Color(0xFFE9EAED)
    )
}
