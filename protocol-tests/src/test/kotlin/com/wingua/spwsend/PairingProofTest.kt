package com.wingua.spwsend

import com.wingua.spwsend.net.*
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class PairingProofTest {
    @Test
    fun `proof rejects replay tampering wrong keys and expired challenges`() {
        val root = Files.createTempDirectory("proof-test").toFile()
        try {
            val server = TransferIdentity.loadOrCreate(File(root, "server.p12"))
            val peer = TransferIdentity.loadOrCreate(File(root, "peer.p12"))
            val attacker = TransferIdentity.loadOrCreate(File(root, "attacker.p12"))
            var now = 0L
            val proof = PairingProof({ server }, { "session" }, { now })
            fun signed(signer: TransferIdentity = peer): Map<String, Any?> =
                PairingProof.sign(
                    mapOf(
                        "id" to "peer",
                        "fingerprint" to peer.fingerprint,
                        "name" to "手机",
                        "token" to "token",
                    ),
                    proof.issue("peer", peer.fingerprint),
                    signer,
                    server.fingerprint,
                )
            val valid = signed()
            assertTrue(proof.verify(valid))
            assertFalse(proof.verify(valid))
            assertFalse(proof.verify(signed() + mapOf("token" to "changed")))
            assertFalse(proof.verify(signed(attacker)))
            val expired = signed()
            now += 31000
            assertFalse(proof.verify(expired))
            val cleared = signed()
            proof.clear()
            assertFalse(proof.verify(cleared))
        } finally {
            root.deleteRecursively()
        }
    }
}
