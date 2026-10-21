package com.xuncorp.voxzen.ui.navigation

import androidx.compose.runtime.Composable

/**
 * 宿主 `AppNavigation` 的编译期桩。
 *
 * 该文件只参与编译，构建时会从插件 jar 中排除；运行时由宿主 fat jar 提供同名实现。
 * 有了它就能像宿主自身代码一样以普通 Composable 调用宿主导航根，
 * 不必再用反射去驱动 Compose（反射调用无法正确进入组合，会导致内容层接管失效）。
 */
@Composable
fun AppNavigation() {
}
