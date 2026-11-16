package com.wingua.spwsend

import com.wingua.spwsend.sync.RemoteProgress
import kotlin.test.*

class ByteProgressTest {
    @Test
    fun `single unfinished large file reports intermediate progress`() {
        val tracker = RemoteProgress {}
        tracker.start("t", "music", "phone", 1)
        tracker.file("t", "large.bin", false, 4000, 0)
        tracker.bytes("t", 1000, "large.bin")
        assertEquals(0, tracker.snapshot!!.done)
        assertEquals(0.25f, tracker.snapshot!!.fraction)
    }

    @Test
    fun `resume and concurrent files do not double count`() {
        val tracker = RemoteProgress {}
        tracker.start("t", "music", "phone", 2)
        tracker.file("t", "first", false, 4000, 2000)
        tracker.file("t", "second", false, 8000, 4000)
        assertEquals(0.5f, tracker.snapshot!!.fraction)
        tracker.complete("t", 4000, false, "first")
        assertEquals(0.75f, tracker.snapshot!!.fraction)
        tracker.bytes("t", 4000, "second")
        tracker.complete("t", 8000, false, "second")
        assertEquals(1f, tracker.snapshot!!.fraction)
        assertEquals(4000, tracker.snapshot!!.bytes)
    }
}
