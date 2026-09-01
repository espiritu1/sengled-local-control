package com.sengled.control

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.ContentSigner
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Security
import java.security.KeyStore
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocketFactory
import javax.net.ssl.TrustManagerFactory

/**
 * Generates self-signed TLS certificates for the embedded MQTT broker.
 * Mirrors the Python tool's cert generation (CA + server cert).
 */
object SslCertGenerator {

    init {
        // Register Bouncy Castle provider if not already present
        if (Security.getProvider("BC") == null) {
            Security.insertProviderAt(org.bouncycastle.jce.provider.BouncyCastleProvider(), 1)
        }
    }

    /**
     * Creates an SSLServerSocketFactory with a self-signed server certificate.
     * The bulb (ESP8266 / AWS IoT C SDK) requires TLS 1.2 and accepts self-signed certs.
     */
    fun createSslServerSocketFactory(): SSLServerSocketFactory {
        val keyPair = generateRsaKeyPair()
        val cert = generateSelfSignedCert(keyPair, "SengledLocalBroker")

        // Build a KeyStore with the server cert + key
        val keyStore = KeyStore.getInstance("PKCS12", "BC").apply {
            load(null, null)
            setKeyEntry("server", keyPair.private, "".toCharArray(), arrayOf(cert))
        }

        // KeyManagerFactory — the broker's identity
        val kmf = KeyManagerFactory.getInstance("PKIX").apply {
            init(keyStore, "".toCharArray())
        }

        // TrustManagerFactory — not critical for a server, but SSLContext needs it
        val trustStore = KeyStore.getInstance("PKCS12", "BC").apply {
            load(null, null)
            setCertificateEntry("ca", cert)
        }
        val tmf = TrustManagerFactory.getInstance("PKIX").apply {
            init(trustStore)
        }

        // Create SSLContext with TLS 1.2 (ESP8266 mbedtls only speaks TLS 1.2)
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(kmf.keyManagers, tmf.trustManagers, SecureRandom())
        }

        return sslContext.serverSocketFactory
    }

    private fun generateRsaKeyPair(): KeyPair {
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048, SecureRandom())
        return kpg.generateKeyPair()
    }

    private fun generateSelfSignedCert(keyPair: KeyPair, cn: String): java.security.cert.X509Certificate {
        val issuer = X500Name("CN=$cn, O=SengledLocal")
        val now = System.currentTimeMillis()
        val notBefore = Date(now)
        val notAfter = Date(now + 365L * 24 * 60 * 60 * 1000) // 1 year

        val builder = JcaX509v3CertificateBuilder(
            issuer,
            BigInteger.valueOf(now),
            notBefore,
            notAfter,
            issuer,  // self-signed: subject = issuer
            keyPair.public
        )

        val signer: ContentSigner = JcaContentSignerBuilder("SHA256withRSA")
            .setProvider("BC")
            .build(keyPair.private)

        val certHolder = builder.build(signer)

        return JcaX509CertificateConverter()
            .setProvider("BC")
            .getCertificate(certHolder)
    }
}
