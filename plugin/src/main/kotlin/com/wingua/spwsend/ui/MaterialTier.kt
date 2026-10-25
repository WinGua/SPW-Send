package com.wingua.spwsend.ui

import java.util.Locale

/**
 * 宿主窗口材质档位。
 *
 * 宿主 `spwWindowStyle` 的取值形如 `None`、`WindowsMica`、`WindowsMicaAlt`、
 * `WindowsAcrylic`，这里归一化成面板背景需要的几档。
 */
internal enum class MaterialTier(val label: String) {
    Classic("经典纯色"),
    Mica("云母"),
    MicaAlt("云母 ALT"),
    Acrylic("亚克力"),
    Unknown("跟随");

    companion object {

        fun fromWindowStyle(style: String?): MaterialTier {
            val value = style?.trim()?.lowercase(Locale.ROOT).orEmpty()
            return when {
                value.isEmpty() -> Unknown
                value == "none" || value.contains("classic") || value.contains("经典") -> Classic
                value.contains("micaalt") || value.contains("mica alt") ||
                    value.contains("云母alt") || value.contains("云母 alt") -> MicaAlt
                value.contains("mica") || value.contains("云母") -> Mica
                value.contains("acrylic") || value.contains("亚克力") -> Acrylic
                else -> Unknown
            }
        }
    }
}
