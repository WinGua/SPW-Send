package com.wingua.spwsend.net

import java.io.File
import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.time.Instant
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.*
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder

/** 每次安装独立的 RSA-2048 自签名身份，不修改宿主的全局 SSL/安全提供者。 */
class TransferIdentity private constructor(val context: SSLContext, val fingerprint: String) {
    companion object {
        fun hash(cert: X509Certificate): String =
            MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString("") {
                "%02x".format(it.toInt() and 255)
            }

        @Synchronized
        fun loadOrCreate(file: File): TransferIdentity {
            val password = CharArray(0)
            val store = KeyStore.getInstance("PKCS12")
            if (file.exists()) {
                file.inputStream().use { store.load(it, password) }
            } else {
                store.load(null, password)
                val pair =
                    KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
                val name = X500Name("CN=SPW-Send")
                val builder =
                    JcaX509v3CertificateBuilder(
                        name,
                        BigInteger(128, SecureRandom()),
                        Date.from(Instant.parse("2020-01-01T00:00:00Z")),
                        Date.from(Instant.parse("2044-01-01T00:00:00Z")),
                        name,
                        pair.public,
                    )
                val cert =
                    JcaX509CertificateConverter()
                        .getCertificate(
                            builder.build(
                                JcaContentSignerBuilder("SHA256withRSA").build(pair.private)
                            )
                        )
                cert.verify(pair.public)
                store.setKeyEntry("identity", pair.private, password, arrayOf(cert))
                file.parentFile.mkdirs()
                val temp = File(file.parentFile, file.name + ".tmp")
                temp.createNewFile()
                if (Files.getFileStore(temp.toPath()).supportsFileAttributeView("posix")) {
                    Files.setPosixFilePermissions(
                        temp.toPath(),
                        setOf(
                            java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                            java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                        ),
                    )
                }
                temp.outputStream().use { store.store(it, password) }
                try {
                    Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE)
                } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                    Files.move(temp.toPath(), file.toPath())
                }
            }
            val cert = store.getCertificate("identity") as X509Certificate
            cert.checkValidity()
            val km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            km.init(store, password)
            return TransferIdentity(
                SSLContext.getInstance("TLS").apply { init(km.keyManagers, null, null) },
                hash(cert),
            )
        }
    }
}

/** 先在 TLS 握手中核对证书，再发送 token/文件。没有 HTTP 或信任任意证书的回退。 */
object PinnedTls {
    private val contexts = ConcurrentHashMap<String, SSLSocketFactory>()

    fun factory(fingerprint: String): SSLSocketFactory {
        require(Regex("[a-f0-9]{64}").matches(fingerprint)) { "请升级对端以支持 HTTPS 证书身份" }
        return contexts.computeIfAbsent(fingerprint) { expected ->
            val trust =
                object : X509TrustManager {
                    override fun getAcceptedIssuers() = emptyArray<X509Certificate>()

                    override fun checkClientTrusted(
                        chain: Array<X509Certificate>,
                        authType: String,
                    ) {
                        throw java.security.cert.CertificateException("不支持客户端证书认证")
                    }

                    override fun checkServerTrusted(
                        chain: Array<X509Certificate>,
                        authType: String,
                    ) {
                        val cert =
                            chain.firstOrNull()
                                ?: throw java.security.cert.CertificateException("缺少证书")
                        cert.checkValidity()
                        if (TransferIdentity.hash(cert) != expected)
                            throw java.security.cert.CertificateException("对端证书身份已改变")
                    }
                }
            SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }.socketFactory
        }
    }
}
