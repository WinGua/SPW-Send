package com.wingua.spwsend.ui

import androidx.compose.ui.graphics.Color
import com.moriafly.salt.ui.SaltColors
import java.awt.Color as AwtColor
import kotlin.math.min

/**
 * 面板底色。
 *
 * 以主题底色为主，按材质档掺入壁纸主色的不同比例：
 * 云母只带一点色调，亚克力保留更多桌面颜色，经典纯色完全不掺。
 */
internal fun panelBackground(
    dark: Boolean,
    tier: MaterialTier,
    wallpaper: Color?,
    sampled: Color? = null,
): Color {
    // 屏幕采样拿到的是宿主已合成好的真实材质色，优先级最高。
    if (sampled != null) return sampled
    val base = if (dark) Color(0xFF202024) else Color(0xFFF5F5F7)
    val ratio = when (tier) {
        MaterialTier.Classic -> 0f
        MaterialTier.Mica -> 0.08f
        MaterialTier.MicaAlt -> 0.10f
        MaterialTier.Acrylic -> 0.22f
        MaterialTier.Unknown -> 0.08f
    }
    if (wallpaper == null || ratio == 0f) return base

    val blended = blend(base, normalizeTint(wallpaper, dark, tier), ratio)
    return if (tier == MaterialTier.MicaAlt) {
        blend(blended, if (dark) Color.Black else Color(0xFFE6E6EB), 0.07f)
    } else {
        blended
    }
}

/** 把壁纸主色收进材质允许的饱和度与亮度区间。 */
private fun normalizeTint(color: Color, dark: Boolean, tier: MaterialTier): Color {
    val maxSaturation = when (tier) {
        MaterialTier.Acrylic -> 0.34f
        MaterialTier.MicaAlt -> 0.16f
        else -> 0.12f
    }
    val hsb = FloatArray(3)
    AwtColor.RGBtoHSB(
        (color.red * 255f).toInt().coerceIn(0, 255),
        (color.green * 255f).toInt().coerceIn(0, 255),
        (color.blue * 255f).toInt().coerceIn(0, 255),
        hsb,
    )
    hsb[1] = min(hsb[1], maxSaturation)
    hsb[2] = if (dark) hsb[2].coerceIn(0.12f, 0.32f) else hsb[2].coerceIn(0.55f, 0.90f)
    return Color(AwtColor.HSBtoRGB(hsb[0], hsb[1], hsb[2]))
}

private fun blend(base: Color, tint: Color, fraction: Float): Color {
    val f = fraction.coerceIn(0f, 1f)
    return Color(
        base.red + (tint.red - base.red) * f,
        base.green + (tint.green - base.green) * f,
        base.blue + (tint.blue - base.blue) * f,
        base.alpha,
    )
}

/**
 * 面板配色。
 *
 * 材质档只决定底色与卡片透明度，卡片本身固定为半透明白 / 黑叠加，
 * 不再随背景颜色漂移，保证文字对比度稳定。
 */
internal fun hostPalette(
    dark: Boolean,
    tier: MaterialTier,
    wallpaper: Color?,
    sampled: Color? = null,
): SaltColors {
    val base = if (dark) SaltColors.defaultDark() else SaltColors.defaultLight()
    if (tier == MaterialTier.Classic) {
        return base.copy(
            background = if (dark) Color(0xFF232323) else Color(0xFFF8F8F8),
            subBackground = if (dark) Color(0xFF2A2A2A) else Color(0xFFFCFCFC),
            popup = if (dark) Color(0xFF2A2A2A) else Color(0xFFFCFCFC),
            text = if (dark) Color(0xFFEDEDEE) else Color(0xFF25272B),
            subText = if (dark) Color(0xFFA0A3AB) else Color(0xFF777B82),
            stroke = if (dark) Color(0xFF37393E) else Color(0xFFE9EAED),
        )
    }

    // 卡片只比底色亮约 8~10 级，与宿主的层级关系一致；亚克力不再额外提亮。
    val cardAlpha = if (dark) 0.05f else if (tier == MaterialTier.Acrylic) 0.56f else 0.72f
    val background = panelBackground(dark, tier, wallpaper, sampled)
    val popup = blend(background, Color.White, if (dark) 0.10f else 0.45f)
    return base.copy(
        background = background,
        subBackground = Color.White.copy(alpha = cardAlpha),
        popup = popup,
        text = if (dark) Color(0xFFEBEEF1) else Color(0xFF25272B),
        subText = if (dark) Color(0xFFCCD0D5) else Color(0xFF5F636B),
        stroke = if (dark) Color.White.copy(alpha = 0.10f) else Color.Black.copy(alpha = 0.08f),
    )
}
