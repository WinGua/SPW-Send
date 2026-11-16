package com.wingua.spwsend

import com.wingua.spwsend.net.PairingVerification
import kotlin.test.*

class PairingVerificationTest {
    @Test
    fun `LocalSend base 260 encoding is independent of endpoint order and case`() {
        val a = "00".repeat(32)
        val b = "ff".repeat(32)
        val expected = listOf(52, 240, 92, 21, 220, 8, 7, 243, 9, 169, 193, 203, 67, 21, 8, 164)
        assertEquals(expected, PairingVerification.of(a, b).indices)
        assertEquals(expected, PairingVerification.of(b.uppercase(), a).indices)
        assertNotEquals(expected, PairingVerification.of(a, "fe".repeat(32)).indices)
        assertEquals(a.uppercase() + b.uppercase(), PairingVerification.of(a, b).combined)
        assertFailsWith<IllegalArgumentException> { PairingVerification.of(a, "invalid") }
    }
}
