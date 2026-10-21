package com.wingua.spwsend.ui

import androidx.compose.ui.graphics.Color
import com.moriafly.salt.ui.SaltColors

/** 宿主「椒盐经典」的窗口样式值（没有 DWM 材质）。 */
internal const val CLASSIC_WINDOW_STYLE = "None"

/**
 * 面板配色。
 *
 * [glass] 为 true 时用于磨砂壁纸背景：卡片使用冷白半透明叠加，让壁纸参与合成；
 * 否则使用与宿主经典主题一致的实色。
 */
internal fun hostPalette(dark: Boolean, glass: Boolean): SaltColors {
    val base = if (dark) SaltColors.defaultDark() else SaltColors.defaultLight()
    if (glass) {
        return if (dark) {
            base.copy(
                background = Color(0xFF202024),
                subBackground = Color(0xFFE3EAF6).copy(alpha = 0.08f),
                popup = Color(0xFF2A2A2A),
                text = Color(0xFFEBEEF1),
                subText = Color(0xFFCCD0D5),
                stroke = Color(0xFFD7E3F5).copy(alpha = 0.12f)
            )
        } else {
            base.copy(
                background = Color(0xFFF5F5F7),
                subBackground = Color.White.copy(alpha = 0.58f),
                popup = Color(0xFFFCFCFC),
                text = Color(0xFF25272B),
                subText = Color(0xFF5F636B),
                stroke = Color.Black.copy(alpha = 0.08f)
            )
        }
    }
    return base.copy(
        background = if (dark) Color(0xFF232323) else Color(0xFFF8F8F8),
        subBackground = if (dark) Color(0xFF2A2A2A) else Color(0xFFFCFCFC),
        popup = if (dark) Color(0xFF2A2A2A) else Color(0xFFFCFCFC),
        text = if (dark) Color(0xFFEDEDEE) else Color(0xFF25272B),
        subText = if (dark) Color(0xFFA0A3AB) else Color(0xFF777B82),
        stroke = if (dark) Color(0xFF37393E) else Color(0xFFE9EAED)
    )
}
