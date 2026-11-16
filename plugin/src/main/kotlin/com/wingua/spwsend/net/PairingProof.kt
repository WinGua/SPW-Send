package com.wingua.spwsend.net

import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.util.Base64

/** First-use approval is separate from proof of possession of the persistent identity key. */
data class PairRequest(
    val id: String,
    val name: String,
    val address: String,
    val fingerprint: String,
)

class PairingProof(
    private val identity: () -> TransferIdentity,
    private val session: () -> String,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private data class Challenge(
        val peer: String,
        val fingerprint: String,
        val nonce: String,
        val expires: Long,
    )

    private val pending = LinkedHashMap<String, Challenge>()
    private val random = SecureRandom()

    private fun random() =
        Base64.getUrlEncoder().encodeToString(ByteArray(32).also(random::nextBytes))

    @Synchronized
    fun issue(id: String, fingerprint: String): Map<String, Any?> {
        pending.entries.removeIf { now() >= it.value.expires }
        while (pending.size >= 128) pending.remove(pending.keys.first())
        val key = random()
        val nonce = random()
        pending[key] = Challenge(id, fingerprint, nonce, now() + 30_000)
        return mapOf(
            "ok" to true,
            "challengeId" to key,
            "challenge" to nonce,
            "fingerprint" to identity().fingerprint,
            "session" to session(),
        )
    }

    @Synchronized
    fun verify(data: Map<String, Any?>): Boolean {
        val key = data["challengeId"] as? String ?: return false
        val challenge = pending.remove(key) ?: return false
        if (
            now() >= challenge.expires ||
                challenge.peer != data["id"] ||
                challenge.fingerprint != data["fingerprint"]
        )
            return false
        return runCatching {
                val pem = data["certificate"] as String
                val proof = data["proof"] as String
                require(pem.length <= 16384 && proof.length <= 2048)
                val cert =
                    CertificateFactory.getInstance("X.509")
                        .generateCertificate(pem.byteInputStream()) as X509Certificate
                cert.checkValidity()
                cert.verify(cert.publicKey)
                require(TransferIdentity.hash(cert) == challenge.fingerprint)
                require((cert.publicKey as RSAPublicKey).modulus.bitLength() >= 2048)
                Signature.getInstance("SHA256withRSA").run {
                    initVerify(cert)
                    update(payload(identity().fingerprint, session(), key, challenge.nonce, data))
                    verify(Base64.getDecoder().decode(proof))
                }
            }
            .getOrDefault(false)
    }

    @Synchronized fun clear() = pending.clear()

    companion object {
        private val fields =
            listOf("id", "fingerprint", "role", "form", "name", "port", "session", "token")

        fun payload(
            serverFingerprint: String,
            serverSession: String,
            id: String,
            nonce: String,
            data: Map<String, Any?>,
        ): ByteArray {
            val out = ByteArrayOutputStream()
            out.write("MySend-Pair-v1\n".toByteArray(Charsets.UTF_8))
            for (value in
                listOf(serverFingerprint, serverSession, id, nonce) +
                    fields.map { (data[it] ?: if (it == "port") 0 else "").toString() }) {
                val bytes = value.toByteArray(Charsets.UTF_8)
                out.write("${bytes.size}:".toByteArray(Charsets.US_ASCII))
                out.write(bytes)
            }
            return out.toByteArray()
        }

        fun sign(
            data: Map<String, Any?>,
            challenge: Map<String, Any?>,
            identity: TransferIdentity,
            expectedFingerprint: String,
        ): Map<String, Any?> {
            require(challenge["fingerprint"] == expectedFingerprint)
            val id = challenge["challengeId"] as String
            val nonce = challenge["challenge"] as String
            val session = challenge["session"] as String
            return data +
                mapOf(
                    "challengeId" to id,
                    "certificate" to identity.certificatePem,
                    "proof" to
                        Base64.getEncoder()
                            .encodeToString(
                                identity.sign(
                                    payload(expectedFingerprint, session, id, nonce, data)
                                )
                            ),
                )
        }
    }
}
