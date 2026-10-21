@file:OptIn(UnstableSpwWorkshopApi::class)

package com.wingua.spwsend

import com.wingua.spwsend.ui.SendController
import com.wingua.spwsend.ui.SendOverlay
import com.wingua.spwsend.ui.SendPanelState
import com.xuncorp.spw.workshop.api.ActionShortcut
import com.xuncorp.spw.workshop.api.PluginContext
import com.xuncorp.spw.workshop.api.PluginPermission
import com.xuncorp.spw.workshop.api.SpwPlugin
import com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi
import com.xuncorp.spw.workshop.api.WorkshopApi
import java.awt.event.KeyEvent
import javax.swing.SwingUtilities

/**
 * SPW-Send 椒盐音乐模组入口。
 *
 * 通过配置页按钮或快捷键（Ctrl+Alt+S）打开同步面板；
 * 面板以 Compose + SaltUI 内嵌在宿主内容区，不创建独立窗口。
 */
class SendPlugin(
    pluginContext: PluginContext
) : SpwPlugin(pluginContext) {

    private var panelHook: AutoCloseable? = null

    override fun start() {
        registerShortcut()
        registerPanelHook()
    }

    override fun stop() {
        runCatching {
            WorkshopApi.manager.keyBindingManager.unregister(actionId = ACTION_OPEN_PANEL)
        }
        runCatching { panelHook?.close() }
        panelHook = null
        SwingUtilities.invokeLater {
            SendPanelState.close()
            SendController.shutdown()
        }
    }

    private fun registerShortcut() {
        if (!WorkshopApi.manager.isPermissionGranted(PluginPermission.KEY_BINDINGS)) return

        // 权限可能在注册瞬间被撤销，注册失败不应导致整个插件启动失败。
        runCatching {
            WorkshopApi.manager.keyBindingManager.register(
                actionId = ACTION_OPEN_PANEL,
                title = "打开 SPW-Send 同步面板",
                defaultShortcut = ActionShortcut(
                    KeyEvent.VK_S,
                    ActionShortcut.CONTROL or ActionShortcut.ALT
                ),
                hasGlobal = true,
                handler = Runnable { openPanel() }
            )
        }
    }

    private fun registerPanelHook() {
        if (!WorkshopApi.manager.isPermissionGranted(PluginPermission.CLASS_TRANSFORM)) {
            WorkshopApi.ui.toast(
                "SPW-Send 需要「界面注入」权限才能显示同步面板",
                WorkshopApi.Ui.ToastType.Warning
            )
            return
        }
        runCatching {
            panelHook = WorkshopApi.hookRegistrar.afterComposable(
                className = "com.xuncorp.voxzen.ui.navigation.AppNavigationKt",
                methodName = "AppNavigation",
                inClass = "androidx.compose.ui.ۄ"
            ) { _ ->
                SendOverlay()
            }
        }.onFailure {
            WorkshopApi.ui.toast(
                "同步面板注入失败，请从插件配置打开",
                WorkshopApi.Ui.ToastType.Warning
            )
        }
    }

    companion object {
        private const val ACTION_OPEN_PANEL = "spw-send-open-panel"

        /** 插件配置页「打开同步面板」按钮的入口，也被快捷键调用。 */
        @JvmStatic
        fun openPanel() {
            SwingUtilities.invokeLater {
                SendPanelState.open()
                SendController.start()
            }
        }
    }
}
