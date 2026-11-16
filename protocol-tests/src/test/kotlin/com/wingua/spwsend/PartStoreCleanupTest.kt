package com.wingua.spwsend

import com.wingua.spwsend.sync.*
import java.nio.file.Files
import kotlin.test.*

class PartStoreCleanupTest {
    @Test
    fun `last discarded part removes directory and preserves other parts`() {
        val root = Files.createTempDirectory("spw-empty-parts").toFile()
        try {
            for (name in listOf("first", "second")) {
                PartStore.writeMeta(root, name, PartMeta(name, 4, 1))
                PartStore.partFile(root, name).writeBytes(byteArrayOf(1, 2))
            }
            val dir = PartStore.partFile(root, "first").parentFile
            PartStore.discard(root, "first")
            assertTrue(PartStore.partFile(root, "second").exists())
            PartStore.discard(root, "second")
            assertFalse(dir.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `startup cleanup removes an empty directory`() {
        val root = Files.createTempDirectory("spw-empty-parts").toFile()
        try {
            val dir = PartStore.partFile(root, "file").parentFile
            dir.mkdirs()
            PartStore.cleanup(root)
            assertFalse(dir.exists())
        } finally {
            root.deleteRecursively()
        }
    }
}
