package com.wingua.spwsend.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** 宿主内嵌同步面板的可见状态。 */
object SendPanelState {

    var visible by mutableStateOf(false)
        private set

    fun open() {
        visible = true
    }

    fun close() {
        visible = false
    }
}
