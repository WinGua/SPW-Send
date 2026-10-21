@file:OptIn(UnstableSpwWorkshopApi::class)

package com.wingua.spwsend

import com.wingua.spwsend.ui.AppearanceSettings
import com.wingua.spwsend.ui.SendController
import com.wingua.spwsend.ui.SendOverlay
import com.wingua.spwsend.ui.SendPanelState
import com.xuncorp.spw.workshop.api.ActionShortcut
import com.xuncorp.spw.workshop.api.PluginContext
import com.xuncorp.spw.workshop.api.PluginPermission
import com.xuncorp.spw.workshop.api.SpwPlugin
import com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi
import com.xuncorp.spw.workshop.api.WorkshopApi
import com.xuncorp.spw.workshop.api.config.ConfigHelper
import java.awt.event.KeyEvent
import java.util.function.Consumer
import javax.swing.SwingUtilities

/**
 * SPW-Send 椒盐音乐模组入口。
 *
 * 通过配置页按钮或快捷键（Ctrl+Alt+S）打开 / 关闭同步面板；
 * 面板通过 `afterComposable` 追加在宿主内容区，不接管宿主导航。
 */
class SendPlugin(
    pluginContext: PluginContext
) : SpwPlugin(pluginContext) {

    private var panelHook: AutoCloseable? = null

    private var injectionWarningShown = false

    private var appearanceListenerAttached = false

    private val configManager by lazy { WorkshopApi.manager.createConfigManager() }

    private val appearanceListener = Consumer<ConfigHelper> { config ->
        val value = config.get(KEY_BACKGROUND_STYLE, "auto")
        SwingUtilities.invokeLater { AppearanceSettings.apply(value) }
    }

    override fun start() {
        applyAppearanceConfig()
        registerShortcut()
        registerPanelHooks()
    }

    override fun stop() {
        if (appearanceListenerAttached) {
            runCatching { configManager.removeConfigChangeListener(appearanceListener) }
            appearanceListenerAttached = false
        }
        runCatching {
            WorkshopApi.manager.keyBindingManager.unregister(actionId = ACTION_OPEN_PANEL)
        }
        closeHooks()
        SwingUtilities.invokeLater {
            SendPanelState.close()
            SendController.shutdown()
        }
    }

    /** 读取「背景外观」设置并监听配置页的修改。 */
    private fun applyAppearanceConfig() {
        runCatching {
            AppearanceSettings.apply(configManager.getConfig().get(KEY_BACKGROUND_STYLE, "auto"))
            if (!appearanceListenerAttached) {
                configManager.addConfigChangeListener(appearanceListener)
                appearanceListenerAttached = true
            }
        }
    }

    private fun registerShortcut() {
        if (!WorkshopApi.manager.isPermissionGranted(PluginPermission.KEY_BINDINGS)) return

        // 权限可能在注册瞬间被撤销，注册失败不应导致整个插件启动失败。
        runCatching {
            WorkshopApi.manager.keyBindingManager.register(
                actionId = ACTION_OPEN_PANEL,
                title = "打开 / 关闭 SPW-Send 同步面板",
                defaultShortcut = ActionShortcut(
                    KeyEvent.VK_S,
                    ActionShortcut.CONTROL or ActionShortcut.ALT
                ),
                hasGlobal = true,
                handler = Runnable { togglePanel() }
            )
        }
    }

    private fun registerPanelHooks() {
        if (!WorkshopApi.manager.isPermissionGranted(PluginPermission.CLASS_TRANSFORM)) {
            if (!injectionWarningShown) {
                injectionWarningShown = true
                WorkshopApi.ui.toast(
                    "SPW-Send 需要「界面注入」权限才能显示同步面板",
                    WorkshopApi.Ui.ToastType.Warning
                )
            }
            return
        }

        // 面板渲染入口：始终追加在宿主导航之后。
        panelHook = runCatching {
            WorkshopApi.hookRegistrar.afterComposable(
                className = TARGET_CLASS,
                methodName = TARGET_METHOD,
                inClass = TARGET_CALLER
            ) { _ ->
                SendOverlay()
            }
        }.onFailure {
            if (!injectionWarningShown) {
                injectionWarningShown = true
                WorkshopApi.ui.toast(
                    "同步面板注入失败，请从插件配置打开",
                    WorkshopApi.Ui.ToastType.Warning
                )
            }
        }.getOrNull()
    }

    private fun closeHooks() {
        runCatching { panelHook?.close() }
        panelHook = null
    }

    companion object {
        private const val ACTION_OPEN_PANEL = "spw-send-open-panel"
        private const val KEY_BACKGROUND_STYLE = "background.style"
        private const val TARGET_CLASS = "com.xuncorp.voxzen.ui.navigation.AppNavigationKt"
        private const val TARGET_METHOD = "AppNavigation"
        private const val TARGET_CALLER = "androidx.compose.ui.ۄ"

        /** 插件配置页「打开同步面板」按钮的入口。 */
        @JvmStatic
        fun openPanel() {
            SwingUtilities.invokeLater {
                SendPanelState.open()
                SendController.start()
            }
        }

        /** 快捷键入口：面板已打开时关闭，否则打开。 */
        @JvmStatic
        fun togglePanel() {
            SwingUtilities.invokeLater {
                if (SendPanelState.visible) {
                    SendController.requestClose()
                } else {
                    SendPanelState.open()
                    SendController.start()
                }
            }
        }
    }
}
