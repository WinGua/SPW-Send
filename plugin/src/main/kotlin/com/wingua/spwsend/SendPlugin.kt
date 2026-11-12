@file:OptIn(UnstableSpwWorkshopApi::class)

package com.wingua.spwsend

import com.wingua.spwsend.core.Diagnostics
import com.wingua.spwsend.ui.HostUiTargets
import com.wingua.spwsend.ui.SendController
import com.wingua.spwsend.ui.SendOverlay
import com.wingua.spwsend.ui.SendPanelState
import com.xuncorp.spw.workshop.api.ActionShortcut
import com.xuncorp.spw.workshop.api.PluginContext
import com.xuncorp.spw.workshop.api.PluginPermission
import com.xuncorp.spw.workshop.api.SpwPlugin
import com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi
import com.xuncorp.spw.workshop.api.WorkshopApi
import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.event.KeyEvent
import java.net.URI
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

    private val hostVersion: String = pluginContext.spwVersion
    private val pluginVersion: String = pluginContext.pluginVersion

    private var panelHook: AutoCloseable? = null

    private var injectionWarningShown = false

    override fun start() {
        val notice = runCatching {
            Diagnostics.install(pluginVersion, hostVersion)
        }.getOrNull()
        if (!notice.isNullOrEmpty()) {
            runCatching {
                WorkshopApi.ui.toast(notice, WorkshopApi.Ui.ToastType.Warning)
            }
        }
        Diagnostics.record("plugin", "start plugin=$pluginVersion host=$hostVersion")
        try {
            registerShortcut()
            registerPanelHooks()
        } catch (t: Throwable) {
            Diagnostics.capture(t, Thread.currentThread().name, "start")
            throw t
        }
    }

    override fun stop() {
        Diagnostics.record("plugin", "stop")
        runCatching {
            WorkshopApi.manager.keyBindingManager.unregister(actionId = ACTION_OPEN_PANEL)
        }.onFailure { Diagnostics.record("shortcut", "注销失败: ${it.message}") }
        closeHooks()
        SwingUtilities.invokeLater {
            try {
                SendPanelState.close()
                SendController.shutdown()
            } catch (t: Throwable) {
                Diagnostics.capture(t, Thread.currentThread().name, "stop")
                throw t
            } finally {
                Diagnostics.uninstall()
            }
        }
    }

    private fun registerShortcut() {
        if (!WorkshopApi.manager.isPermissionGranted(PluginPermission.KEY_BINDINGS)) {
            Diagnostics.record("shortcut", "未授予快捷键权限")
            return
        }

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
        }.onFailure {
            Diagnostics.record("shortcut", "注册失败: ${it.message}")
        }
    }

    private fun registerPanelHooks() {
        if (!WorkshopApi.manager.isPermissionGranted(PluginPermission.CLASS_TRANSFORM)) {
            Diagnostics.record("hook", "未授予界面注入权限")
            if (!injectionWarningShown) {
                injectionWarningShown = true
                WorkshopApi.ui.toast(
                    "SPW-Send 需要「界面注入」权限才能显示同步面板",
                    WorkshopApi.Ui.ToastType.Warning
                )
            }
            return
        }

        // 宿主的 Compose 调用点类名随版本重新混淆，运行时动态定位，版本映射仅作兜底。
        val candidates = HostUiTargets.resolve(hostVersion)
        if (candidates.isEmpty()) {
            Diagnostics.record("hook", "SPW $hostVersion 未找到注入点")
            if (!injectionWarningShown) {
                injectionWarningShown = true
                WorkshopApi.ui.toast(
                    "SPW $hostVersion 尚未适配，同步面板无法显示",
                    WorkshopApi.Ui.ToastType.Warning
                )
            }
            return
        }

        // 面板渲染入口：始终追加在宿主导航之后。
        panelHook = candidates.firstNotNullOfOrNull { caller ->
            runCatching {
                WorkshopApi.hookRegistrar.afterComposable(
                    className = TARGET_CLASS,
                    methodName = TARGET_METHOD,
                    inClass = caller
                ) { _ ->
                    try {
                        SendOverlay()
                    } catch (t: Throwable) {
                        Diagnostics.capture(t, Thread.currentThread().name, "compose")
                        throw t
                    }
                }
            }.onFailure {
                Diagnostics.record("hook", "注入失败($caller): ${it.message}")
            }.getOrNull()
        }

        if (panelHook == null) {
            Diagnostics.record("hook", "同步面板注入失败（SPW $hostVersion）")
            if (!injectionWarningShown) {
                injectionWarningShown = true
                WorkshopApi.ui.toast(
                    "同步面板注入失败（SPW $hostVersion）",
                    WorkshopApi.Ui.ToastType.Warning
                )
            }
        }
    }

    private fun closeHooks() {
        runCatching { panelHook?.close() }
        panelHook = null
    }

    companion object {
        private const val ACTION_OPEN_PANEL = "spw-send-open-panel"
        private const val TARGET_CLASS = "com.xuncorp.voxzen.ui.navigation.AppNavigationKt"
        private const val TARGET_METHOD = "AppNavigation"

        /** 插件配置页「打开同步面板」按钮的入口。 */
        @JvmStatic
        fun openPanel() {
            Diagnostics.record("panel", "通过配置页打开")
            SwingUtilities.invokeLater {
                SendPanelState.open()
                SendController.start()
            }
        }

        /** 快捷键入口：面板已打开时关闭，否则打开。 */
        @JvmStatic
        fun togglePanel() {
            Diagnostics.record("panel", "快捷键切换")
            SwingUtilities.invokeLater {
                if (SendPanelState.visible) {
                    SendController.requestClose()
                } else {
                    SendPanelState.open()
                    SendController.start()
                }
            }
        }

        /** 配置页快捷键提示；不跳转宿主设置，只说明修改位置。 */
        @JvmStatic
        fun showShortcutHint() {
            WorkshopApi.ui.toast(
                "默认 Ctrl+Alt+S；可在宿主「设置 → 快捷键」中修改「打开 / 关闭 SPW-Send 同步面板」",
                WorkshopApi.Ui.ToastType.Warning
            )
        }

        /** 配置页打开诊断目录。 */
        @JvmStatic
        fun openDiagnostics() {
            Diagnostics.record("diagnostics", "打开目录")
            val folder = Diagnostics.folder()
            val opened = runCatching { Desktop.getDesktop().open(folder) }.isSuccess
            if (!opened) {
                val copied = copyToClipboard(folder.absolutePath)
                val message = if (copied) {
                    "无法打开目录，路径已复制：${folder.absolutePath}"
                } else {
                    "无法打开目录：${folder.absolutePath}"
                }
                WorkshopApi.ui.toast(message, WorkshopApi.Ui.ToastType.Warning)
            } else if (!Diagnostics.hasCrashFiles()) {
                WorkshopApi.ui.toast(
                    "诊断目录已打开，暂无崩溃记录",
                    WorkshopApi.Ui.ToastType.Warning
                )
            }
        }

        /** 配置页导出诊断包：后台打包，完成后打开所在目录。 */
        @JvmStatic
        fun exportDiagnostics() {
            Diagnostics.record("diagnostics", "导出诊断包")
            Thread({
                val result = runCatching { Diagnostics.exportPackage() }
                SwingUtilities.invokeLater {
                    val zip = result.getOrNull()
                    if (zip != null) {
                        Diagnostics.record("diagnostics", "导出成功 ${zip.name}")
                        runCatching { Desktop.getDesktop().open(zip.parentFile) }
                        WorkshopApi.ui.toast(
                            "诊断包已导出：${zip.name}",
                            WorkshopApi.Ui.ToastType.Success
                        )
                    } else {
                        val error = result.exceptionOrNull()?.message ?: "未知错误"
                        Diagnostics.record("diagnostics", "导出失败: $error")
                        WorkshopApi.ui.toast(
                            "导出失败：$error",
                            WorkshopApi.Ui.ToastType.Error
                        )
                    }
                }
            }, "spw-send-diagnostics-export").apply { isDaemon = true }.start()
        }

        /** 配置页问题反馈：打开预填模板的 GitHub 新建 issue 页面。 */
        @JvmStatic
        fun openFeedback() {
            Diagnostics.record("diagnostics", "打开问题反馈")
            Thread({
                val url = Diagnostics.feedbackUrl()
                val opened = runCatching {
                    Desktop.getDesktop().browse(URI.create(url))
                }.isSuccess
                val copied = if (!opened) copyToClipboard(url) else false
                SwingUtilities.invokeLater {
                    when {
                        opened -> WorkshopApi.ui.toast(
                            "已打开反馈页，请拖入诊断包",
                            WorkshopApi.Ui.ToastType.Success
                        )
                        copied -> WorkshopApi.ui.toast(
                            "无法打开浏览器，反馈链接已复制",
                            WorkshopApi.Ui.ToastType.Warning
                        )
                        else -> WorkshopApi.ui.toast(
                            "无法打开浏览器：$url",
                            WorkshopApi.Ui.ToastType.Warning
                        )
                    }
                }
            }, "spw-send-feedback").apply { isDaemon = true }.start()
        }

        private fun copyToClipboard(text: String): Boolean =
            runCatching {
                Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
            }.isSuccess
    }
}
