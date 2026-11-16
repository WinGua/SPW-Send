package com.wingua.spwsend

import com.wingua.spwsend.core.Diagnostics
import java.util.zip.ZipFile
import kotlin.test.*

class DiagnosticsExportTest {
    @Test
    fun `export includes recent operations after transfer has stopped`() {
        Diagnostics.record("transfer", "send bytes=1048576 elapsedMs=50")
        val file = Diagnostics.exportPackage()
        ZipFile(file).use { zip ->
            val text = zip.getInputStream(zip.getEntry("state.txt")).bufferedReader().readText()
            assertTrue(text.contains("send bytes=1048576 elapsedMs=50"))
        }
    }
}
