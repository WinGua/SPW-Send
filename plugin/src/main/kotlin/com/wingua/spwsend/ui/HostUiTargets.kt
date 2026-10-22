package com.wingua.spwsend.ui

import java.io.ByteArrayInputStream
import java.io.DataInputStream

/**
 * 定位宿主 AppNavigation 调用点的混淆类名。
 *
 * 该类名由 Compose 编译器生成并随宿主版本重新混淆，不能硬编码；
 * 运行时从宿主 jar 中筛选出引用 AppNavigationKt.AppNavigation 的类，
 * 再按版本映射兜底。
 */
internal object HostUiTargets {

    private const val TARGET_INTERNAL_NAME = "com/xuncorp/voxzen/ui/navigation/AppNavigationKt"
    private const val TARGET_METHOD = "AppNavigation"
    private const val CLASS_FILE_MAGIC = -0x35014542 // 0xCAFEBABE 的有符号 Int 形式

    fun resolve(hostVersion: String): List<String> {
        val fallback = fallbackFor(hostVersion)
        val dynamic = runCatching { scanHostJar() }.getOrDefault(emptyList())
        // 已知版本优先使用验证过的类名，未知版本依赖动态扫描结果。
        return (listOfNotNull(fallback) + dynamic).distinct()
    }

    private fun fallbackFor(version: String): String? = when (version) {
        "1.19.0" -> "androidx.compose.ui.\u06c4"
        "1.19.1" -> "androidx.compose.ui.\u0443"
        else -> null
    }

    private fun scanHostJar(): List<String> {
        val jar = HostJar.find() ?: return emptyList()
        val result = mutableListOf<String>()
        val entries = jar.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            val name = entry.name
            if (entry.isDirectory || !name.startsWith("androidx/compose/ui/") || !name.endsWith(".class")) {
                continue
            }
            val simpleName = name.removePrefix("androidx/compose/ui/").removeSuffix(".class")
            if (simpleName.contains('/')) continue
            val bytes = jar.getInputStream(entry).use { it.readBytes() }
            if (referencesTarget(bytes)) {
                result += "androidx.compose.ui.$simpleName"
            }
        }
        return result
    }

    /** 只解析常量池，判断类中是否存在对 AppNavigationKt.AppNavigation 的方法引用。 */
    private fun referencesTarget(bytes: ByteArray): Boolean = runCatching {
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            if (input.readInt() != CLASS_FILE_MAGIC) return@use false
            input.readUnsignedShort()
            input.readUnsignedShort()
            val constantPoolCount = input.readUnsignedShort()

            val utf8 = HashMap<Int, String>()
            val classEntries = HashMap<Int, Int>()
            val nameAndTypeEntries = HashMap<Int, Int>()
            val methodRefs = mutableListOf<Pair<Int, Int>>()

            var index = 1
            while (index < constantPoolCount) {
                when (val tag = input.readUnsignedByte()) {
                    1 -> utf8[index] = input.readUTF()
                    7 -> classEntries[index] = input.readUnsignedShort()
                    9, 11 -> input.skipNBytes(4)
                    10 -> {
                        val classIndex = input.readUnsignedShort()
                        val nameAndTypeIndex = input.readUnsignedShort()
                        methodRefs += classIndex to nameAndTypeIndex
                    }
                    8 -> input.skipNBytes(2)
                    3, 4 -> input.skipNBytes(4)
                    5, 6 -> {
                        input.skipNBytes(8)
                        index++
                    }
                    12 -> {
                        val nameIndex = input.readUnsignedShort()
                        input.skipNBytes(2)
                        nameAndTypeEntries[index] = nameIndex
                    }
                    15 -> input.skipNBytes(3)
                    16 -> input.skipNBytes(2)
                    17, 18 -> input.skipNBytes(4)
                    19, 20 -> input.skipNBytes(2)
                    else -> return@use false
                }
                index++
            }

            methodRefs.any { (classIndex, nameAndTypeIndex) ->
                val className = utf8[classEntries[classIndex] ?: -1]
                val methodName = utf8[nameAndTypeEntries[nameAndTypeIndex] ?: -1]
                className == TARGET_INTERNAL_NAME && methodName == TARGET_METHOD
            }
        }
    }.getOrDefault(false)
}
