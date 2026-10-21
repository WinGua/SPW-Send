package com.wingua.spwsend.ui

/** 背景外观选项。 */
internal enum class BackgroundStyle(val value: String) {
    Auto("auto"),
    Glass("glass"),
    Solid("solid");

    companion object {

        fun fromValue(value: String?): BackgroundStyle =
            entries.firstOrNull { it.value.equals(value, ignoreCase = true) } ?: Auto

        /**
         * 跟随宿主时：只有「椒盐经典」（无窗口材质）使用纯色，
         * 其余材质样式以及读取不到窗口样式的情况都使用磨砂壁纸背景。
         */
        fun usesGlass(style: BackgroundStyle, hostStyle: String?): Boolean = when (style) {
            Glass -> true
            Solid -> false
            Auto -> hostStyle != CLASSIC_WINDOW_STYLE
        }
    }
}
