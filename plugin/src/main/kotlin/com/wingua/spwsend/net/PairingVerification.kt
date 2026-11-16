package com.wingua.spwsend.net

import java.math.BigInteger
import java.security.MessageDigest
import java.util.Locale

/** LocalSend comparison, using its fixed 260-symbol alphabet. */
data class PairingVerification(val combined: String, val indices: List<Int>) {
    companion object {
        const val ALPHABET_SIZE = 260

        fun of(local: String, remote: String): PairingVerification {
            require(
                Regex("[a-fA-F0-9]{64}").matches(local) && Regex("[a-fA-F0-9]{64}").matches(remote)
            )
            val combined =
                listOf(local.uppercase(Locale.ROOT), remote.uppercase(Locale.ROOT))
                    .sorted()
                    .joinToString("")
            var value =
                BigInteger(
                    1,
                    MessageDigest.getInstance("SHA-256")
                        .digest(combined.toByteArray(Charsets.UTF_8))
                        .copyOfRange(0, 16),
                )
            val base = BigInteger.valueOf(ALPHABET_SIZE.toLong())
            val indices = MutableList(16) { 0 }
            for (i in indices.indices.reversed()) {
                val parts = value.divideAndRemainder(base)
                indices[i] = parts[1].toInt()
                value = parts[0]
            }
            return PairingVerification(combined, indices.toList())
        }
    }
}
