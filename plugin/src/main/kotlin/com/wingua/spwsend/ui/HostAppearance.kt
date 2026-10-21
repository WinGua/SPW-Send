package com.wingua.spwsend.ui

import java.lang.reflect.Modifier

/** 宿主当前外观（读取失败时字段为 null，走系统深浅色与纯色回退）。 */
data class HostAppearance(
    val windowStyle: String?,
    val dark: Boolean?
)

/**
 * 通过反射读取椒盐音乐 `AppConfig`，避免编译期依赖宿主内部类。
 *
 * 宿主字段被混淆或重命名时全部返回 null，不影响面板使用。
 */
object HostAppearanceReader {

    private const val APP_CONFIG_CLASS = "com.xuncorp.voxzen.util.AppConfig"

    fun read(): HostAppearance = runCatching {
        val clazz = Class.forName(APP_CONFIG_CLASS)
        val instance = runCatching { clazz.getField("INSTANCE").get(null) }.getOrNull()
        val style = readMember(clazz, instance, "spwWindowStyle")
        val theme = readMember(clazz, instance, "lightDarkTheme")
        HostAppearance(
            windowStyle = style,
            dark = when (theme) {
                "Light" -> false
                "Dark" -> true
                else -> null
            }
        )
    }.getOrDefault(HostAppearance(null, null))

    private fun readMember(clazz: Class<*>, instance: Any?, property: String): String? {
        val getterName = "get" + property.replaceFirstChar { it.uppercase() }
        val getter = clazz.methods.firstOrNull {
            it.name == getterName && it.parameterCount == 0
        }
        if (getter != null) {
            val target = if (Modifier.isStatic(getter.modifiers)) null else instance
            return runCatching { getter.invoke(target)?.toString() }.getOrNull()
        }
        return runCatching {
            val field = clazz.getField(property)
            val target = if (Modifier.isStatic(field.modifiers)) null else instance
            field.get(target)?.toString()
        }.getOrNull()
    }
}
