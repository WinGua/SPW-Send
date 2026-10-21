package com.wingua.spwsend.ui

/** 背景外观选项，与宿主窗口材质一一对应。 */
internal enum class BackgroundStyle(val value: String) {
    Auto("auto"),
    Mica("mica"),
    Acrylic("acrylic"),
    MicaAlt("micaAlt"),
    Classic("classic"),
    Gradient("gradient");

    companion object {

        fun fromValue(value: String?): BackgroundStyle =
            entries.firstOrNull { it.value.equals(value, ignoreCase = true) } ?: Auto

        /**
         * 把外观选项换算成配色所用的窗口样式；
         * [Auto] 直接使用宿主读取结果，经典与渐变不使用材质配色。
         */
        fun resolveWindowStyle(style: BackgroundStyle, hostStyle: String?): String? = when (style) {
            Auto -> hostStyle
            Mica -> "WindowsMica"
            Acrylic -> "WindowsAcrylic"
            MicaAlt -> "WindowsMicaAlt"
            Classic, Gradient -> null
        }
    }
}
