package com.wingua.spwsend.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** 宿主内嵌同步面板的可见状态。 */
object SendPanelState {

    var visible by mutableStateOf(false)
        private set

    /** 是否已成功接管宿主内容层；只有此时透明背景才真正透出 DWM 材质。 */
    var materialBridgeAvailable by mutableStateOf(false)
        internal set

    fun open() {
        visible = true
    }

    fun close() {
        visible = false
    }

    /** 返回键 / ESC 是否已消费本次事件。 */
    fun consumeBack(): Boolean {
        if (!visible) return false
        close()
        return true
    }
}
