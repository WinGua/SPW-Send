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

    /** 内容层替换 hook 是否注册成功。 */
    var materialHookRegistered by mutableStateOf(false)
        internal set

    /** 内容层替换 hook 注册失败的原因。 */
    var materialHookError by mutableStateOf<String?>(null)
        internal set

    fun open() {
        visible = true
    }

    fun close() {
        visible = false
    }
}
