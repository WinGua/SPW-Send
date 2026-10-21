package com.wingua.spwsend.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LayoutModifier
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.Constraints
import com.xuncorp.voxzen.ui.navigation.AppNavigation

/**
 * 宿主导航根（`AppNavigationKt.AppNavigation`）的桥接。
 *
 * 面板打开时只保留宿主组合、不进行布局与绘制，
 * 让窗口的 DWM 云母 / 亚克力背景直接透出；关闭后宿主内容原样恢复，
 * 同时保留导航状态与滚动位置。
 *
 * 这里的 `AppNavigation()` 是普通 Composable 调用，编译期使用
 * `com.xuncorp.voxzen.ui.navigation` 桩、运行时链接宿主 fat jar 中的真实实现。
 */
@Composable
fun HostMaterialLayer() {
    DisposableEffect(Unit) {
        SendPanelState.materialBridgeAvailable = true
        onDispose { SendPanelState.materialBridgeAvailable = false }
    }
    RetainedHostContent(SendPanelState.visible)
}

private data class HostPlacement(val hidden: Boolean) : LayoutModifier {
    override fun MeasureScope.measure(
        measurable: Measurable,
        constraints: Constraints
    ): MeasureResult {
        val child = measurable.measure(constraints)
        return layout(child.width, child.height) {
            if (!hidden) child.placeRelative(0, 0)
        }
    }
}

@Composable
private fun RetainedHostContent(hidden: Boolean) {
    val focus = LocalFocusManager.current
    LaunchedEffect(hidden) {
        if (hidden) focus.clearFocus(force = true)
    }
    Box(Modifier.fillMaxSize().then(HostPlacement(hidden))) {
        AppNavigation()
    }
}
