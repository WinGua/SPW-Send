package com.wingua.spwsend

import com.wingua.spwsend.net.HttpResponse
import com.wingua.spwsend.net.HttpServerLite
import com.wingua.spwsend.net.TransferIdentity
import java.net.HttpURLConnection
import kotlin.test.Test
import kotlin.test.assertFails

class LegacyTransportTest {
    @Test
    fun `transport port must reject plaintext`() {
        val root = java.nio.file.Files.createTempDirectory("spw-tls-test").toFile()
        val server =
            HttpServerLite(TransferIdentity.loadOrCreate(java.io.File(root, "identity.p12"))) {
                HttpResponse(200, "ok".toByteArray())
            }
        try {
            val port = server.start(0)
            assertFails {
                val request =
                    java.net.URI("http://127.0.0.1:$port/v2/hello").toURL().openConnection()
                        as HttpURLConnection
                request.connectTimeout = 2000
                request.readTimeout = 2000
                try {
                    request.inputStream.use { it.readBytes() }
                } finally {
                    request.disconnect()
                }
            }
        } finally {
            server.stop()
            root.deleteRecursively()
        }
    }
}
