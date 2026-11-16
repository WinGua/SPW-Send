package com.wingua.spwsend.sync

import com.sun.jna.Function
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Platform
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.channels.Channels
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.FileTime

/** Mapping-relative access. Every child directory and final file rejects links. */
internal class FileScope(root: File) : Closeable {
    private val directories = mutableListOf<BoundDirectory>()
    private val base = root.toPath().toRealPath()
    private val rootDir =
        own(if (Platform.isWindows()) WindowsDirectory.root(base) else PosixDirectory.root(base))

    private fun own(dir: BoundDirectory): BoundDirectory = dir.also { directories.add(it) }

    fun directory(relative: String, create: Boolean = false): BoundDirectory {
        var current = rootDir
        for (name in components(relative)) current = own(current.child(name, create))
        return current
    }

    fun parent(relative: String, create: Boolean = false): Pair<BoundDirectory, String> {
        val parts = components(relative)
        var current = rootDir
        for (name in parts.dropLast(1)) current = own(current.child(name, create))
        return current to parts.last()
    }

    override fun close() {
        var error: Exception? = null
        for (dir in directories.asReversed()) try {
            dir.close()
        } catch (e: Exception) {
            if (error == null) error = e else error.addSuppressed(e)
        }
        directories.clear()
        error?.let { throw it }
    }

    private fun components(relative: String): List<String> {
        val path = Path.of(relative)
        val parts = path.map { it.toString() }
        if (
            path.isAbsolute ||
                parts.isEmpty() ||
                parts.any {
                    it.isEmpty() ||
                        it == "." ||
                        it == ".." ||
                        it.any { c -> c == ':' || c == '\\' || c == '/' || c == '\u0000' }
                }
        )
            throw IOException("非法映射相对路径")
        return parts
    }
}

internal data class BoundEntry(
    val name: String,
    val regular: Boolean,
    val size: Long,
    val modified: Long,
)

internal interface BoundDirectory : Closeable {
    fun child(name: String, create: Boolean): BoundDirectory

    fun stat(name: String): BoundEntry?

    fun read(name: String): InputStream

    fun write(name: String, append: Boolean): OutputStream

    fun modified(name: String, millis: Long)

    fun delete(name: String, directory: Boolean = false)

    fun entries(): List<BoundEntry>

    fun publish(name: String, target: BoundDirectory, newName: String): Boolean
}

private class PosixDirectory
private constructor(private val fd: Int, private val stream: SecureDirectoryStream<Path>) :
    BoundDirectory {
    companion object {
        private val libc = NativeLibrary.getInstance(Platform.C_LIBRARY_NAME)

        private fun call(name: String, vararg args: Any?): Int =
            libc.getFunction(name).invokeInt(args)

        private val flags =
            if (Platform.isMac()) 0x100000 or 0x100 or 0x1000000 else 0x10000 or 0x20000 or 0x80000

        fun root(path: Path): PosixDirectory = wrap(call("open", path.toString(), flags))

        private fun wrap(fd: Int): PosixDirectory {
            if (fd < 0) throw failure(Native.getLastError())
            try {
                val directory = Files.newDirectoryStream(Path.of("/dev/fd/$fd"))
                if (directory !is SecureDirectoryStream<Path>) {
                    directory.close()
                    throw IOException("文件系统不支持安全目录句柄")
                }
                return PosixDirectory(fd, directory)
            } catch (e: Exception) {
                call("close", fd)
                throw e
            }
        }

        private fun failure(code: Int): IOException =
            if (code == 2) NoSuchFileException("mapping child")
            else FileSystemException("mapping child", null, "errno=$code")
    }

    override fun child(name: String, create: Boolean): BoundDirectory {
        if (create && call("mkdirat", fd, name, 511) != 0) {
            val code = Native.getLastError()
            if (code != 17) throw failure(code)
        }
        return wrap(call("openat", fd, name, flags))
    }

    override fun stat(name: String): BoundEntry? =
        try {
            val attr =
                stream
                    .getFileAttributeView(
                        Path.of(name),
                        BasicFileAttributeView::class.java,
                        LinkOption.NOFOLLOW_LINKS,
                    )
                    .readAttributes()
            BoundEntry(
                name,
                attr.isRegularFile && !attr.isSymbolicLink,
                attr.size(),
                attr.lastModifiedTime().toMillis(),
            )
        } catch (_: NoSuchFileException) {
            null
        }

    override fun read(name: String): InputStream {
        requireRegular(name, false)
        return Channels.newInputStream(
            stream.newByteChannel(
                Path.of(name),
                setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS),
            )
        )
    }

    override fun write(name: String, append: Boolean): OutputStream {
        requireRegular(name, true)
        val mode = if (append) StandardOpenOption.APPEND else StandardOpenOption.TRUNCATE_EXISTING
        return Channels.newOutputStream(
            stream.newByteChannel(
                Path.of(name),
                setOf(
                    StandardOpenOption.WRITE,
                    StandardOpenOption.CREATE,
                    mode,
                    LinkOption.NOFOLLOW_LINKS,
                ),
            )
        )
    }

    private fun requireRegular(name: String, missing: Boolean) {
        val entry = stat(name)
        if (entry == null && !missing) throw NoSuchFileException(name)
        if (entry != null && !entry.regular) throw IOException("分片路径不是普通文件")
    }

    override fun modified(name: String, millis: Long) {
        requireRegular(name, false)
        stream
            .getFileAttributeView(
                Path.of(name),
                BasicFileAttributeView::class.java,
                LinkOption.NOFOLLOW_LINKS,
            )
            .setTimes(FileTime.fromMillis(millis), null, null)
    }

    override fun delete(name: String, directory: Boolean) {
        if (directory) stream.deleteDirectory(Path.of(name)) else stream.deleteFile(Path.of(name))
    }

    override fun entries(): List<BoundEntry> = stream.mapNotNull { stat(it.fileName.toString()) }

    override fun publish(name: String, target: BoundDirectory, newName: String): Boolean {
        requireRegular(name, false)
        target as PosixDirectory
        val result =
            if (Platform.isMac()) call("renameatx_np", fd, name, target.fd, newName, 4)
            else
                try {
                    call("renameat2", fd, name, target.fd, newName, 1)
                } catch (_: UnsatisfiedLinkError) {
                    -2
                }
        if (result == 0) return true
        val code = if (result == -2) 38 else Native.getLastError()
        if (code == 17) return false
        if (code != 22 && code != 38 && code != 45 && code != 95) throw failure(code)
        // A hard link is also atomic and refuses an existing destination. Never use replacing
        // rename.
        if (call("linkat", fd, name, target.fd, newName, 0) != 0) {
            val linkError = Native.getLastError()
            if (linkError == 17) return false else throw failure(linkError)
        }
        delete(name)
        return true
    }

    override fun close() {
        try {
            stream.close()
        } finally {
            call("close", fd)
        }
    }
}

/** Windows native relative opens avoid reparsing a parent pathname between checks and I/O. */
private class WindowsDirectory private constructor(private val handle: Pointer) : BoundDirectory {
    companion object {
        private val options = mapOf(Library.OPTION_CALLING_CONVENTION to Function.ALT_CONVENTION)
        private val kernel by lazy { NativeLibrary.getInstance("kernel32", options) }
        private val nt by lazy { NativeLibrary.getInstance("ntdll", options) }

        private fun k(name: String, vararg args: Any?): Int =
            kernel.getFunction(name).invokeInt(args)

        private fun n(name: String, vararg args: Any?): Int = nt.getFunction(name).invokeInt(args)

        private fun failure(code: Int): IOException =
            when (code) {
                2,
                3 -> NoSuchFileException("mapping child")
                80,
                183 -> FileAlreadyExistsException("mapping child")
                145 -> DirectoryNotEmptyException("mapping child")
                else -> FileSystemException("mapping child", null, "Windows error=$code")
            }

        private fun ntFailure(status: Int): IOException =
            failure(n("RtlNtStatusToDosError", status))

        fun root(path: Path): WindowsDirectory {
            if (Native.POINTER_SIZE != 8) throw IOException("需要 64 位 JVM")
            val plain = path.toString()
            val absolute =
                if (plain.startsWith("\\\\")) "\\\\?\\UNC\\" + plain.substring(2)
                else "\\\\?\\" + plain
            val h =
                kernel
                    .getFunction("CreateFileW")
                    .invokePointer(
                        arrayOf(WString(absolute), 0x100081, 3, null, 3, 0x02200000, null)
                    )
            if (h == null || Pointer.nativeValue(h) == -1L) throw failure(Native.getLastError())
            return try {
                checkDirectory(h)
                WindowsDirectory(h)
            } catch (e: Exception) {
                k("CloseHandle", h)
                throw e
            }
        }

        private fun attributes(h: Pointer): Int =
            Memory(8).use { out ->
                if (k("GetFileInformationByHandleEx", h, 9, out, 8) == 0)
                    throw failure(Native.getLastError())
                out.getInt(0)
            }

        private fun checkDirectory(h: Pointer) {
            val attr = attributes(h)
            if (attr and 0x400 != 0 || attr and 0x10 == 0) throw IOException("映射子目录不能是符号链接或目录联接")
        }

        private fun relative(
            parent: Pointer,
            name: String,
            directory: Boolean,
            create: Boolean,
            access: Int,
            allowLink: Boolean = false,
        ): Pointer {
            val bytes = name.toByteArray(Charsets.UTF_16LE)
            Memory((bytes.size + 2).toLong()).use { text ->
                Memory(16).use { unicode ->
                    Memory(48).use { obj ->
                        Memory(16).use { status ->
                            text.clear()
                            text.write(0, bytes, 0, bytes.size)
                            unicode.clear()
                            unicode.setShort(0, bytes.size.toShort())
                            unicode.setShort(2, (bytes.size + 2).toShort())
                            unicode.setPointer(8, text)
                            obj.clear()
                            obj.setInt(0, 48)
                            obj.setPointer(8, parent)
                            obj.setPointer(16, unicode)
                            obj.setInt(24, 0x40)
                            val out = PointerByReference()
                            val result =
                                n(
                                    "NtCreateFile",
                                    out,
                                    access or 0x100080,
                                    obj,
                                    status,
                                    null,
                                    0,
                                    if (directory) 3 else 7,
                                    if (create) 3 else 1,
                                    0x200020 or if (directory) 1 else 0x40,
                                    null,
                                    0,
                                )
                            if (result < 0) throw ntFailure(result)
                            val h = out.value
                            return try {
                                if (directory) checkDirectory(h)
                                else if (!allowLink && attributes(h) and (0x400 or 0x10) != 0)
                                    throw IOException("分片路径不是普通文件")
                                h
                            } catch (e: Exception) {
                                k("CloseHandle", h)
                                throw e
                            }
                        }
                    }
                }
            }
        }

        private fun info(h: Pointer, name: String): BoundEntry =
            Memory(24).use { standard ->
                Memory(40).use { basic ->
                    if (k("GetFileInformationByHandleEx", h, 1, standard, 24) == 0)
                        throw failure(Native.getLastError())
                    if (k("GetFileInformationByHandleEx", h, 0, basic, 40) == 0)
                        throw failure(Native.getLastError())
                    BoundEntry(
                        name,
                        basic.getInt(32) and (0x400 or 0x10) == 0,
                        standard.getLong(8),
                        basic.getLong(16) / 10000 - 11644473600000L,
                    )
                }
            }

        private fun dispose(h: Pointer) =
            Memory(1).use { delete ->
                delete.setByte(0, 1)
                if (k("SetFileInformationByHandle", h, 4, delete, 1) == 0)
                    throw failure(Native.getLastError())
            }
    }

    override fun child(name: String, create: Boolean): BoundDirectory =
        WindowsDirectory(relative(handle, name, true, create, 1))

    override fun stat(name: String): BoundEntry? =
        try {
            val h = relative(handle, name, false, false, 0, allowLink = true)
            try {
                info(h, name)
            } finally {
                k("CloseHandle", h)
            }
        } catch (_: NoSuchFileException) {
            null
        }

    override fun read(name: String): InputStream {
        val h = relative(handle, name, false, false, 0x80000000.toInt())
        return object : InputStream() {
            private var closed = false

            override fun read(): Int {
                val bytes = ByteArray(1)
                return if (read(bytes) == -1) -1 else bytes[0].toInt() and 255
            }

            override fun read(bytes: ByteArray, off: Int, len: Int): Int {
                java.util.Objects.checkFromIndexSize(off, len, bytes.size)
                if (len == 0) return 0
                val buffer = if (off == 0 && len == bytes.size) bytes else ByteArray(len)
                val count = IntByReference()
                if (k("ReadFile", h, buffer, len, count, null) == 0)
                    throw failure(Native.getLastError())
                val amount = count.value
                if (amount == 0) return -1
                if (buffer !== bytes) System.arraycopy(buffer, 0, bytes, off, amount)
                return amount
            }

            override fun close() {
                if (!closed) {
                    closed = true
                    k("CloseHandle", h)
                }
            }
        }
    }

    override fun write(name: String, append: Boolean): OutputStream {
        val h = relative(handle, name, false, true, 0x40000000)
        try {
            if (append) {
                if (k("SetFilePointerEx", h, 0L, null, 2) == 0) throw failure(Native.getLastError())
            } else
                Memory(8).use { size ->
                    size.clear()
                    if (k("SetFileInformationByHandle", h, 6, size, 8) == 0)
                        throw failure(Native.getLastError())
                }
        } catch (e: Exception) {
            k("CloseHandle", h)
            throw e
        }
        return object : OutputStream() {
            private var closed = false

            override fun write(value: Int) = write(byteArrayOf(value.toByte()))

            override fun write(bytes: ByteArray, off: Int, len: Int) {
                java.util.Objects.checkFromIndexSize(off, len, bytes.size)
                var position = off
                val end = off + len
                while (position < end) {
                    val buffer =
                        if (position == 0 && end == bytes.size) bytes
                        else bytes.copyOfRange(position, end)
                    val count = IntByReference()
                    if (k("WriteFile", h, buffer, buffer.size, count, null) == 0)
                        throw failure(Native.getLastError())
                    if (count.value <= 0) throw IOException("分片写入未完成")
                    position += count.value
                }
            }

            override fun close() {
                if (!closed) {
                    closed = true
                    k("CloseHandle", h)
                }
            }
        }
    }

    override fun modified(name: String, millis: Long) {
        val h = relative(handle, name, false, false, 0x100)
        try {
            Memory(40).use { basic ->
                basic.clear()
                basic.setLong(16, (millis + 11644473600000L) * 10000)
                if (k("SetFileInformationByHandle", h, 0, basic, 40) == 0)
                    throw failure(Native.getLastError())
            }
        } finally {
            k("CloseHandle", h)
        }
    }

    override fun delete(name: String, directory: Boolean) {
        val h = relative(handle, name, directory, false, 0x10000, allowLink = true)
        try {
            dispose(h)
        } finally {
            k("CloseHandle", h)
        }
    }

    override fun entries(): List<BoundEntry> {
        val entries = mutableListOf<BoundEntry>()
        Memory(65536).use { buffer ->
            Memory(16).use { ios ->
                var restart = true
                while (true) {
                    val status =
                        n(
                            "NtQueryDirectoryFile",
                            handle,
                            null,
                            null,
                            null,
                            ios,
                            buffer,
                            65536,
                            2,
                            0.toByte(),
                            null,
                            if (restart) 1.toByte() else 0.toByte(),
                        )
                    restart = false
                    if (status == 0x80000006.toInt()) break
                    if (status < 0) throw ntFailure(status)
                    val length = ios.getLong(8)
                    var offset = 0L
                    while (offset < length) {
                        val next = buffer.getInt(offset).toLong()
                        val bytes = buffer.getInt(offset + 60)
                        if (bytes < 0 || offset + 68 + bytes > length) throw IOException("无效目录记录")
                        val name =
                            String(buffer.getByteArray(offset + 68, bytes), Charsets.UTF_16LE)
                        val attr = buffer.getInt(offset + 56)
                        if (name != "." && name != "..")
                            entries.add(
                                BoundEntry(
                                    name,
                                    attr and (0x400 or 0x10) == 0,
                                    buffer.getLong(offset + 40),
                                    buffer.getLong(offset + 24) / 10000 - 11644473600000L,
                                )
                            )
                        if (next == 0L) break
                        if (next < 68 || offset + next >= length) throw IOException("无效目录偏移")
                        offset += next
                    }
                }
            }
        }
        return entries
    }

    override fun publish(name: String, target: BoundDirectory, newName: String): Boolean {
        target as WindowsDirectory
        val h = relative(handle, name, false, false, 0x10000)
        try {
            val bytes = newName.toByteArray(Charsets.UTF_16LE)
            Memory(maxOf(24, 22 + bytes.size).toLong()).use { rename ->
                Memory(16).use { ios ->
                    rename.clear()
                    rename.setPointer(8, target.handle)
                    rename.setInt(16, bytes.size)
                    rename.write(20, bytes, 0, bytes.size)
                    val status =
                        n("NtSetInformationFile", h, ios, rename, rename.size().toInt(), 10)
                    if (status < 0) {
                        val error = ntFailure(status)
                        if (error is FileAlreadyExistsException) return false else throw error
                    }
                    return true
                }
            }
        } finally {
            k("CloseHandle", h)
        }
    }

    override fun close() {
        k("CloseHandle", handle)
    }
}
