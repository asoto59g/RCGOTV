package com.abcgeomag.rcgotv

import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

object Tls {
    fun pairingContext(identity: ClientIdentity): SSLContext = createContext(identity, null)

    fun authenticatedContext(identity: ClientIdentity, expectedFingerprint: String): SSLContext {
        val normalized = expectedFingerprint.filterNot { it == ':' || it.isWhitespace() }.lowercase()
        require(normalized.matches(Regex("[0-9a-f]{64}"))) { "Invalid paired TV certificate fingerprint" }
        return createContext(identity, normalized)
    }

    fun context(identity: ClientIdentity): SSLContext = pairingContext(identity)

    private fun createContext(identity: ClientIdentity, expectedFingerprint: String?): SSLContext {
        val keyStore = KeyStore.getInstance("PKCS12").apply { load(null, null) }
        val password = "wifitv".toCharArray()
        keyStore.setKeyEntry("client", identity.key, password, arrayOf(identity.cert))

        val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(keyStore, password)
        }

        val trustManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                val leaf = chain?.firstOrNull() ?: throw java.security.cert.CertificateException("TV supplied no server certificate")
                if (expectedFingerprint == null) return
                val expected = expectedFingerprint.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                val actual = MessageDigest.getInstance("SHA-256").digest(leaf.encoded)
                if (!MessageDigest.isEqual(expected, actual)) {
                    throw java.security.cert.CertificateException("TV certificate fingerprint does not match the paired certificate")
                }
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }

        return SSLContext.getInstance("TLS").apply {
            init(keyManagers.keyManagers, arrayOf<TrustManager>(trustManager), SecureRandom())
        }
    }
}