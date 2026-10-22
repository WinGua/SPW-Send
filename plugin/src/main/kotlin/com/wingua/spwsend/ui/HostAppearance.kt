package com.wingua.spwsend.ui

import java.lang.reflect.Modifier
import java.util.Locale

/** 宿主当前外观（读取失败时为空，走系统深浅色回退）。 */
data class HostAppearance(
    val dark: Boolean? = null,
    val windowStyle: String? = null,
    val fontStrategy: String? = null,
    val customFontFilePath: String? = null,
)

/**
 * 通过反射读取椒盐音乐 `AppConfig`，避免编译期依赖宿主内部类。
 *
 * 宿主字段被混淆或重命名时返回空值，不影响面板使用。
 */
object HostAppearanceReader {

    private const val APP_CONFIG_CLASS = "com.xuncorp.voxzen.util.AppConfig"

    fun read(): HostAppearance = runCatching {
        val clazz = Class.forName(APP_CONFIG_CLASS)
        val instance = runCatching { clazz.getField("INSTANCE").get(null) }.getOrNull()
        HostAppearance(
            dark = when (readMember(clazz, instance, "lightDarkTheme")?.trim()?.lowercase(Locale.ROOT)) {
                "light" -> false
                "dark" -> true
                else -> null
            },
            windowStyle = readMember(clazz, instance, "spwWindowStyle"),
            fontStrategy = readMember(clazz, instance, "fontStrategy"),
            customFontFilePath = readMember(clazz, instance, "customFontFilePath"),
        )
    }.getOrDefault(HostAppearance())

    /** 宿主类加载器，用于读取宿主内置字体等资源。 */
    fun hostClassLoader(): ClassLoader? =
        runCatching { Class.forName(APP_CONFIG_CLASS).classLoader }.getOrNull()
            ?: Thread.currentThread().contextClassLoader
            ?: ClassLoader.getSystemClassLoader()

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
