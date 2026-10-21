package com.wingua.spwsend.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.InternalComposeApi
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.currentComposer
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LayoutModifier
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.Constraints
import java.lang.reflect.Method
import java.lang.reflect.Modifier as JvmModifier

/**
 * 宿主导航根（`AppNavigationKt.AppNavigation`）的桥接。
 *
 * 面板打开时只保留宿主组合、不进行布局与绘制，
 * 让窗口的 DWM 云母 / 亚克力背景直接透出；关闭后宿主内容原样恢复，
 * 同时保留导航状态与滚动位置。
 */
@OptIn(InternalComposeApi::class)
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
        HostAppNavigation()
    }
}

/** 通过反射调用宿主原导航组件；插件自身的调用不经过 hook，因此不会递归。 */
@OptIn(InternalComposeApi::class)
@Composable
private fun HostAppNavigation() {
    val method = HostNavigation.method() ?: return
    // currentComposer 是 Composable 属性，先在组合上下文取出再传入普通 lambda。
    val composer = currentComposer
    runCatching { method.invoke(null, composer, 0) }
}

/** 宿主导航组件是否可反射调用；不可用时放弃内容层替换，保留不透明回退面板。 */
internal fun hostNavigationAvailable(): Boolean = HostNavigation.method() != null

private object HostNavigation {

    private const val CLASS_NAME = "com.xuncorp.voxzen.ui.navigation.AppNavigationKt"
    private const val METHOD_NAME = "AppNavigation"

    @Volatile
    private var cached: Method? = null

    fun method(): Method? {
        cached?.let { return it }
        val resolved = runCatching {
            val loader = HostAppearanceReader.hostClassLoader()
            Class.forName(CLASS_NAME, false, loader)
                .getDeclaredMethod(
                    METHOD_NAME,
                    androidx.compose.runtime.Composer::class.java,
                    Int::class.javaPrimitiveType
                )
                .also { check(JvmModifier.isStatic(it.modifiers)) }
                .also { runCatching { it.isAccessible = true } }
        }.getOrNull()
        cached = resolved
        return resolved
    }
}
