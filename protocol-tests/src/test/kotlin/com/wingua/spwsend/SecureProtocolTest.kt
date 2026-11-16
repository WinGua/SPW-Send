package com.wingua.spwsend

import com.wingua.spwsend.net.*
import com.wingua.spwsend.sync.*
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class SecureProtocolTest {
    private fun temp() = Files.createTempDirectory("spw-protocol-test").toFile()

    @Test
    fun `public discovery fields cannot impersonate an already trusted identity`() {
        val root = temp()
        try {
            val identity = TransferIdentity.loadOrCreate(File(root, "server.p12"))
            val victim = TransferIdentity.loadOrCreate(File(root, "victim.p12"))
            val config = com.wingua.spwsend.core.Config("pc", "pc", 0, emptyList(), mapOf("victim" to victim.fingerprint))
            val endpoints = PcEndpoints(
                identityProvider = { identity }, sessions = SyncLease(), progress = RemoteProgress {},
                trustIdentity = { _, _ -> }, sessionProvider = { "server" }, configProvider = { config },
                busyProvider = { false }, onPeerDisconnected = {}, onPeerPaired = { _, _, _ -> }, onRemoteLog = { _, _ -> },
            )
            val bytes = com.wingua.spwsend.core.Json.stringify(mapOf(
                "id" to "victim", "name" to "forged", "role" to "android", "proto" to 2,
                "fingerprint" to victim.fingerprint,
            )).toByteArray()
            val request = HttpRequest("POST", "/v2/pair", emptyMap(), emptyMap(), bytes.inputStream(), bytes.size.toLong())
            assertEquals(401, endpoints.handle(request).status, "Public fingerprints must not grant a fresh token")
        } finally { root.deleteRecursively() }
    }

    @Test
    fun `cancel verifies owner and preserves lease until active requests exit`() {
        val lease = SyncLease()
        assertTrue(lease.acquire("phone", "task"))
        assertTrue(lease.enter("phone", "task"))
        assertFalse(lease.cancel("stranger", "task"))
        assertTrue(lease.matches("phone", "task"))
        assertTrue(lease.cancel("phone", "task"))
        assertFalse(lease.enter("phone", "task"))
        assertFalse(lease.acquire("other", "new"))
        lease.leave()
        assertTrue(lease.acquire("other", "new"))
    }

    @Test
    fun `identity persists across restart`() {
        val root = temp()
        try {
            val file = File(root, "identity.p12")
            assertEquals(
                TransferIdentity.loadOrCreate(file).fingerprint,
                TransferIdentity.loadOrCreate(file).fingerprint,
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `wrong certificate receives no request bytes`() {
        val root = temp()
        val requests = AtomicInteger()
        val identity = TransferIdentity.loadOrCreate(File(root, "identity.p12"))
        val server =
            HttpServerLite(identity) {
                requests.incrementAndGet()
                HttpResponse(200, "ok".toByteArray())
            }
        try {
            server.start(0)
            assertFails {
                HttpClientLite.get("127.0.0.1", "0".repeat(64), server.port, "/v2/hello")
            }
            assertEquals(0, requests.get())
            assertFails { HttpClientLite.get("127.0.0.1", "", server.port, "/v2/hello") }
            assertEquals(0, requests.get())
        } finally {
            server.stop()
            root.deleteRecursively()
        }
    }

    @Test
    fun `sequential requests reuse TLS connection`() {
        val root = temp()
        val ports = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
        val identity = TransferIdentity.loadOrCreate(File(root, "identity.p12"))
        val server =
            HttpServerLite(identity) { req ->
                ports.add(req.remotePort)
                HttpResponse(200, "ok".toByteArray())
            }
        try {
            server.start(0)
            repeat(10) {
                assertEquals(
                    "ok",
                    HttpClientLite.get("127.0.0.1", identity.fingerprint, server.port, "/v2/hello")
                        .text(),
                )
            }
            assertEquals(1, ports.size)
        } finally {
            server.stop()
            root.deleteRecursively()
        }
    }

    @Test
    fun `two competing owners have only one winner`() {
        val lease = SyncLease()
        val pool = Executors.newFixedThreadPool(2)
        val gate = CountDownLatch(1)
        try {
            repeat(50) { i ->
                val attempts =
                    (0..1).map { owner ->
                        pool.submit<Boolean> {
                            gate.await()
                            lease.acquire("$owner", "$i-$owner")
                        }
                    }
                gate.countDown()
                val values = attempts.map { it.get() }
                assertEquals(1, values.count { it })
                val owner = values.indexOf(true)
                lease.release("$owner", "$i-$owner")
                assertFalse(lease.busy())
            }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `active requests survive lease timeout and delayed release`() {
        var now = 0L
        val events = ArrayList<String>()
        val lease = SyncLease(now = { now }, onEnded = { _, reason -> events.add(reason) })
        assertTrue(lease.acquire("a", "old"))
        assertTrue(lease.enter("a", "old"))
        now = 100000L
        assertFalse(lease.acquire("b", "new"))
        lease.release("a", "old")
        assertFalse(lease.acquire("b", "new"))
        lease.leave()
        assertTrue(lease.acquire("b", "new"))
        lease.release("a", "old")
        assertTrue(lease.matches("b", "new"))
        assertEquals(1, events.size)
    }

    @Test
    fun `cancel task interrupts pending response and prevents new file requests`() {
        val root = temp()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val identity = TransferIdentity.loadOrCreate(File(root, "identity.p12"))
        val server =
            HttpServerLite(identity) {
                entered.countDown()
                release.await()
                HttpResponse(200, "ok".toByteArray())
            }
        val pool = Executors.newSingleThreadExecutor()
        try {
            server.start(0)
            val response = pool.submit {
                HttpClientLite.get(
                    "127.0.0.1",
                    identity.fingerprint,
                    server.port,
                    "/v2/file",
                    headers = mapOf("X-MySend-Sync" to "cancelled"),
                    readTimeoutMs = 3000,
                )
            }
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            val start = System.nanoTime()
            HttpClientLite.cancelTask("cancelled")
            assertTrue(System.nanoTime() - start < 1_000_000_000, "取消不能等待读取超时")
            assertFailsWith<java.util.concurrent.ExecutionException> {
                response.get(1, java.util.concurrent.TimeUnit.SECONDS)
            }
            assertFails {
                HttpClientLite.get(
                    "127.0.0.1",
                    identity.fingerprint,
                    server.port,
                    "/v2/file",
                    headers = mapOf("X-MySend-Sync" to "cancelled"),
                )
            }
        } finally {
            release.countDown()
            HttpClientLite.clearTask("cancelled")
            pool.shutdownNow()
            server.stop()
            root.deleteRecursively()
        }
    }

    @Test
    fun `shutdown keeps lease until file workers exit`() {
        val lease = SyncLease()
        assertTrue(lease.acquire("a", "old"))
        assertTrue(lease.enter("a", "old"))
        lease.clear()
        assertFalse(lease.acquire("b", "new"))
        assertFalse(lease.enter("a", "old"))
        lease.leave()
        assertTrue(lease.acquire("b", "new"))
    }

    @Test
    fun `corrupt configuration does not erase certificate trust`() {
        val root = temp()
        try {
            val file = File(root, "config.json")
            file.writeText("{broken")
            assertFails { com.wingua.spwsend.core.ConfigStore(root).load() }
            assertEquals("{broken", file.readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `finished progress is not overwritten by release`() {
        val progress = RemoteProgress {}
        val lease = SyncLease(onEnded = progress::interrupt)
        lease.acquire("a", "task")
        progress.start("task", "音乐", "手机", 1)
        progress.file("task", "song.flac", false)
        progress.bytes("task", 1024)
        progress.complete("task", 1024, false)
        progress.finish("task", sent = 0, received = 1)
        lease.release("a", "task")
        assertTrue(progress.snapshot!!.finished)
        assertNull(progress.snapshot!!.error)
        assertEquals(1024, progress.snapshot!!.bytes)
    }
}
