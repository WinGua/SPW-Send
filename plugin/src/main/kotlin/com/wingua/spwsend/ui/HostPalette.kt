package com.wingua.spwsend.ui

import androidx.compose.ui.graphics.Color
import com.moriafly.salt.ui.SaltColors

/** 由宿主窗口绘制背景的材质样式。 */
internal val MATERIAL_WINDOW_STYLES = setOf(
    "WindowsAcrylic",
    "WindowsMica",
    "WindowsMicaAlt",
)

/**
 * 按宿主窗口样式还原配色。
 *
 * 面板不接管宿主导航、也不竞争窗口材质，因此这里用取样值做出与宿主一致的实色近似：
 * 经典主题用经典页面色，亚克力 / 云母 / 云母 Alt 用对应材质的聚焦底色与卡片色。
 */
internal fun hostPalette(windowStyle: String?, dark: Boolean): SaltColors {
    val base = if (dark) SaltColors.defaultDark() else SaltColors.defaultLight()
    if (windowStyle !in MATERIAL_WINDOW_STYLES) {
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
        val acrylic = windowStyle == "WindowsAcrylic"
        return base.copy(
            background = if (acrylic) Color(0xFF505050) else Color(0xFF272727),
            subBackground = if (acrylic) Color(0xFF5F5F5F) else Color(0xFF444444),
            popup = Color(0xFF2A2A2A),
            text = Color(0xFFEBEEF1),
            subText = Color(0xFFCCD0D5),
            stroke = if (acrylic) Color(0xFF737375) else Color(0xFF616163)
        )
    }
    return when (windowStyle) {
        "WindowsAcrylic" -> base.copy(
            background = Color(0xFFDDDDDD),
            subBackground = Color(0xFFE2E2E2),
            popup = Color(0xFFFCFCFC),
            text = Color(0xFF1E1715),
            subText = Color(0xFF5F5F5F),
            stroke = Color(0xFFC7C7C7)
        )

        "WindowsMicaAlt" -> base.copy(
            background = Color(0xFFEDEDED),
            subBackground = Color(0xFFFBFBFB),
            popup = Color(0xFFFCFCFC),
            text = Color(0xFF25272B),
            subText = Color(0xFF777B82),
            stroke = Color(0xFFE1E1E1)
        )

        else -> base.copy(
            background = Color(0xFFF5F5F5),
            subBackground = Color(0xFFFCFCFC),
            popup = Color(0xFFFCFCFC),
            text = Color(0xFF25272B),
            subText = Color(0xFF777B82),
            stroke = Color(0xFFE4E4E4)
        )
    }
}
