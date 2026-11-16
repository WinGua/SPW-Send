package com.wingua.spwsend

import com.wingua.spwsend.sync.*
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.FileTime
import kotlin.test.*

class PartStoreSecurityTest {
    private fun linkDirectory(link: java.nio.file.Path, target: java.nio.file.Path) {
        if (System.getProperty("os.name").startsWith("Windows")) {
            val result =
                ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), target.toString())
                    .redirectErrorStream(true)
                    .start()
            val output = result.inputStream.bufferedReader().readText()
            assertEquals(0, result.waitFor(), output)
        } else Files.createSymbolicLink(link, target)
    }

    @Test
    fun `part directory symlink cannot write outside mapping`() {
        val temp = Files.createTempDirectory("spw-part-escape")
        val root = Files.createDirectory(temp.resolve("root")).toFile()
        val outside = Files.createDirectory(temp.resolve("private"))
        try {
            linkDirectory(root.toPath().resolve(".mysend-part"), outside)
            try {
                PartStore.writeMeta(root, "track", PartMeta("track", 3, 1))
            } catch (_: java.io.IOException) {}
            Files.list(outside).use {
                assertEquals(0L, it.count(), "Metadata must not be created outside the mapping")
            }
        } finally {
            val link = root.toPath().resolve(".mysend-part")
            if (Files.exists(link, LinkOption.NOFOLLOW_LINKS)) Files.delete(link)
            temp.toFile().deleteRecursively()
        }
    }

    @Test
    fun `cleanup does not traverse a linked part directory`() {
        val temp = Files.createTempDirectory("spw-part-cleanup-escape")
        val root = Files.createDirectory(temp.resolve("root")).toFile()
        val outside = Files.createDirectory(temp.resolve("private"))
        val victim = Files.write(outside.resolve("private.part"), byteArrayOf(42))
        Files.setLastModifiedTime(victim, FileTime.fromMillis(1))
        try {
            linkDirectory(root.toPath().resolve(".mysend-part"), outside)
            try {
                PartStore.cleanup(root)
            } catch (_: java.io.IOException) {}
            assertTrue(Files.exists(victim), "Cleanup must not delete outside files")
        } finally {
            val link = root.toPath().resolve(".mysend-part")
            if (Files.exists(link, LinkOption.NOFOLLOW_LINKS)) Files.delete(link)
            temp.toFile().deleteRecursively()
        }
    }

    @Test
    fun `atomic publication never replaces a concurrently created target`() {
        val temp = Files.createTempDirectory("spw-part-promote-race").toFile()
        try {
            val part = File(temp, "part")
            part.writeText("incoming")
            val target =
                object : File(temp, "target") {
                    override fun toPath(): java.nio.file.Path {
                        File(temp, "target").writeText("concurrent")
                        return super.toPath()
                    }
                }
            assertFalse(PartStore.promote(part, target))
            assertEquals("concurrent", target.readText())
            assertEquals("incoming", part.readText())
        } finally {
            temp.deleteRecursively()
        }
    }

    @Test
    fun `held part directory never follows a replaced parent name`() {
        val temp = Files.createTempDirectory("spw-part-parent-race")
        val root = Files.createDirectory(temp.resolve("root")).toFile()
        val outside = Files.createDirectory(temp.resolve("private"))
        val cache = Files.createDirectory(root.toPath().resolve(".mysend-part"))
        var replaced = false
        try {
            FileScope(root).use { scope ->
                val held = scope.directory(".mysend-part")
                try {
                    Files.move(cache, root.toPath().resolve("old"))
                    replaced = true
                } catch (error: java.io.IOException) {
                    if (!System.getProperty("os.name").startsWith("Windows")) throw error
                }
                if (replaced) linkDirectory(cache, outside)
                held.write("payload.part", false).use { it.write(byteArrayOf(1, 2, 3)) }
                assertEquals(3L, held.stat("payload.part")!!.size)
            }
            Files.list(outside).use { assertEquals(0L, it.count()) }
        } finally {
            if (replaced) Files.delete(cache)
            temp.toFile().deleteRecursively()
        }
    }

    @Test
    fun `canonical destination remains relative to an aliased mapping root`() {
        val temp = Files.createTempDirectory("spw-part-root-alias")
        val actual = Files.createDirectory(temp.resolve("actual")).toFile()
        val alias = temp.resolve("alias")
        try {
            linkDirectory(alias, actual.toPath())
            PartStore.openPart(alias.toFile(), "nested/track", false).use {
                it.write(byteArrayOf(1, 2, 3))
            }
            val target = File(actual, "nested/track").canonicalFile
            assertTrue(
                PartStore.promote(PartStore.partFile(alias.toFile(), "nested/track"), target)
            )
            assertContentEquals(byteArrayOf(1, 2, 3), target.readBytes())
        } finally {
            Files.deleteIfExists(alias)
            temp.toFile().deleteRecursively()
        }
    }
}
