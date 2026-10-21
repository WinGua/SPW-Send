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
 * [glass] 为 true 时用于壁纸 / 渐变背景：卡片与分隔线使用半透明叠加，
 * 文字保持足够对比度；否则使用与宿主一致的实色近似。
 */
internal fun hostPalette(windowStyle: String?, dark: Boolean, glass: Boolean): SaltColors {
    val base = if (dark) SaltColors.defaultDark() else SaltColors.defaultLight()
    if (glass) {
        val acrylic = windowStyle == "WindowsAcrylic"
        val micaAlt = windowStyle == "WindowsMicaAlt"
        return if (dark) {
            base.copy(
                background = Color(0xFF202024),
                subBackground = when {
                    acrylic -> Color(0xFFD7E3F5).copy(alpha = 0.10f)
                    micaAlt -> Color(0xFFEAF0F8).copy(alpha = 0.07f)
                    else -> Color(0xFFE3EAF6).copy(alpha = 0.08f)
                },
                popup = Color(0xFF2A2A2A),
                text = Color(0xFFEBEEF1),
                subText = Color(0xFFCCD0D5),
                stroke = Color(0xFFD7E3F5).copy(alpha = if (acrylic) 0.16f else 0.12f)
            )
        } else {
            base.copy(
                background = Color(0xFFF5F5F7),
                subBackground = Color.White.copy(alpha = if (micaAlt) 0.50f else 0.58f),
                popup = Color(0xFFFCFCFC),
                text = Color(0xFF25272B),
                subText = Color(0xFF5F636B),
                stroke = Color.Black.copy(alpha = 0.08f)
            )
        }
    }
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
