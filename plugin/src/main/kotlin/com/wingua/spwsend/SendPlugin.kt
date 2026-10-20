@file:OptIn(UnstableSpwWorkshopApi::class)

package com.wingua.spwsend

import com.xuncorp.spw.workshop.api.ActionShortcut
import com.xuncorp.spw.workshop.api.PluginContext
import com.xuncorp.spw.workshop.api.PluginPermission
import com.xuncorp.spw.workshop.api.SpwPlugin
import com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi
import com.xuncorp.spw.workshop.api.WorkshopApi
import java.awt.event.KeyEvent

/**
 * SPW-Send 椒盐音乐模组入口。
 *
 * 当前仅完成工程骨架：注册一个用于打开同步面板的快捷键，
 * 面板、发现、对比与传输均尚未实现。
 */
class SendPlugin(
    pluginContext: PluginContext
) : SpwPlugin(pluginContext) {

    private val keyBindings = WorkshopApi.manager.keyBindingManager

    override fun start() {
        if (!WorkshopApi.manager.isPermissionGranted(PluginPermission.KEY_BINDINGS)) return

        keyBindings.register(
            actionId = ACTION_OPEN_PANEL,
            title = "打开 SPW-Send 同步面板",
            defaultShortcut = ActionShortcut(
                KeyEvent.VK_S,
                ActionShortcut.CONTROL or ActionShortcut.ALT
            ),
            hasGlobal = true,
            handler = { openPanel() }
        )
    }

    override fun stop() {
        runCatching { keyBindings.unregister(actionId = ACTION_OPEN_PANEL) }
    }

    companion object {
        private const val ACTION_OPEN_PANEL = "spw-send-open-panel"

        /** 插件配置页「打开同步面板」按钮的入口，也被快捷键调用。 */
        @JvmStatic
        @JvmName("openPanel")
        fun openPanel() {
            WorkshopApi.ui.toast("同步面板尚未实现", WorkshopApi.Ui.ToastType.Warning)
        }
    }
}
