package com.wingua.spwsend.ui

import java.io.File
import java.net.JarURLConnection
import java.util.jar.JarEntry
import java.util.jar.JarFile

/**
 * 定位并读取宿主 jar，供动态类名与内置资源查找复用。
 *
 * 优先通过宿主内部类的资源 URL 定位，失败时再按 classpath 查找。
 */
internal object HostJar {

    private val ANCHOR_CLASSES = listOf(
        "com.xuncorp.voxzen.ui.navigation.AppNavigationKt",
        "com.xuncorp.voxzen.util.AppConfig",
    )

    @Volatile
    private var cached: JarFile? = null

    fun find(): JarFile? = cached ?: locate()?.also { cached = it }

    private fun locate(): JarFile? {
        for (anchor in ANCHOR_CLASSES) {
            val clazz = loadHostClass(anchor) ?: continue
            val resource = runCatching {
                clazz.getResource(anchor.substringAfterLast('.') + ".class")
            }.getOrNull() ?: continue
            val connection = runCatching {
                resource.openConnection() as? JarURLConnection
            }.getOrNull() ?: continue
            runCatching { connection.jarFile }.getOrNull()?.let { return it }
        }

        val anchorEntries = ANCHOR_CLASSES.map { it.replace('.', '/') + ".class" }.toSet()
        val classPath = System.getProperty("java.class.path") ?: return null
        for (path in classPath.split(File.pathSeparator)) {
            val file = File(path)
            if (!file.isFile) continue
            val jar = runCatching { JarFile(file) }.getOrNull() ?: continue
            if (anchorEntries.any { jar.getEntry(it) != null }) return jar
            runCatching { jar.close() }
        }
        return null
    }

    /** 按文件名后缀查找资源；多个命中时取路径最短的。 */
    fun readEntryBySuffix(suffix: String): ByteArray? {
        val jar = find() ?: return null
        var best: JarEntry? = null
        val entries = jar.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            if (entry.isDirectory) continue
            if (!entry.name.endsWith(suffix, ignoreCase = true)) continue
            if (best == null || entry.name.length < best!!.name.length) best = entry
        }
        val entry = best ?: return null
        return runCatching { jar.getInputStream(entry).use { it.readBytes() } }.getOrNull()
    }

    private fun loadHostClass(className: String): Class<*>? {
        val loaders = sequenceOf(
            HostJar::class.java.classLoader,
            Thread.currentThread().contextClassLoader,
            ClassLoader.getSystemClassLoader()
        ).filterNotNull()
        return loaders.firstNotNullOfOrNull { loader ->
            runCatching { Class.forName(className, false, loader) }.getOrNull()
        }
    }
}
