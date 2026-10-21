package com.wingua.spwsend.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** 模组配置页「背景外观」的运行时状态。 */
object AppearanceSettings {

    var backgroundStyle by mutableStateOf(BackgroundStyle.Auto)
        private set

    fun apply(value: String?) {
        backgroundStyle = BackgroundStyle.fromValue(value)
    }
}
