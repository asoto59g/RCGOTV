package com.abcgeomag.rcgotv

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import java.util.Locale

data class ClientIdentity(
    val key: PrivateKey,
    val cert: X509Certificate,
    val tvFingerprint: String? = null
)

object CertificateStore {
    private const val PREFERENCES = "tv_identities"

    private fun bcProvider() = BouncyCastleProvider()

    private fun legacyHostKey(host: String) =
        host.replace(Regex("[^0-9A-Za-z_.-]"), "_")

    private fun hostKey(host: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(host.toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") {
            "%02x".format(Locale.ROOT, it.toInt() and 0xff)
        }
        return "h_$hex"
    }

    private fun readIdentity(
        preferences: SharedPreferences,
        id: String
    ): Pair<PrivateKey, X509Certificate>? {
        val encodedKey = preferences.getString("k_$id", null) ?: return null
        val encodedCertificate = preferences.getString("c_$id", null) ?: return null

        return try {
            val key = KeyFactory.getInstance("RSA").generatePrivate(
                PKCS8EncodedKeySpec(Base64.decode(encodedKey, Base64.NO_WRAP))
            )
            val certificate = java.security.cert.CertificateFactory.getInstance("X.509")
                .generateCertificate(
                    Base64.decode(encodedCertificate, Base64.NO_WRAP).inputStream()
                ) as X509Certificate
            key to certificate
        } catch (_: Exception) {
            null
        }
    }

    fun loadOrCreate(context: Context, host: String): ClientIdentity {
        val preferences = context.applicationContext
            .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val id = hostKey(host)
        val legacyId = legacyHostKey(host)

        val identity = readIdentity(preferences, id)
            ?: readIdentity(preferences, legacyId)?.also { _ ->
                preferences.edit()
                    .putString("k_$id", preferences.getString("k_$legacyId", null))
                    .putString("c_$id", preferences.getString("c_$legacyId", null))
                    .commit()
            }
            ?: run {
                val keyPair = KeyPairGenerator.getInstance("RSA")
                    .apply { initialize(2048) }
                    .generateKeyPair()
                val now = Date()
                val end = Date(now.time + 1000L * 60 * 60 * 24 * 3650)
                val subject = X500Name("CN=RCGOTV,O=Android,OU=Remote,C=SV")
                val provider = bcProvider()
                val holder = JcaX509v3CertificateBuilder(
                    subject,
                    BigInteger(160, SecureRandom()),
                    now,
                    end,
                    subject,
                    keyPair.public
                ).build(
                    JcaContentSignerBuilder("SHA256withRSA")
                        .setProvider(provider)
                        .build(keyPair.private)
                )
                val certificate = JcaX509CertificateConverter()
                    .setProvider(provider)
                    .getCertificate(holder)

                preferences.edit()
                    .putString(
                        "k_$id",
                        Base64.encodeToString(keyPair.private.encoded, Base64.NO_WRAP)
                    )
                    .putString(
                        "c_$id",
                        Base64.encodeToString(certificate.encoded, Base64.NO_WRAP)
                    )
                    .apply()

                keyPair.private to certificate
            }

        return ClientIdentity(
            identity.first,
            identity.second,
            loadTvFingerprint(preferences, host, id, legacyId)
        )
    }

    fun fingerprint(certificate: X509Certificate): String =
        MessageDigest.getInstance("SHA-256")
            .digest(certificate.encoded)
            .joinToString("") {
                "%02x".format(Locale.ROOT, it.toInt() and 0xff)
            }

    fun saveTvFingerprint(context: Context, host: String, certificate: X509Certificate) {
        saveTvFingerprint(context, host, fingerprint(certificate))
    }

    fun saveTvFingerprint(context: Context, host: String, fingerprint: String) {
        val normalized = fingerprint
            .filterNot { it == ':' || it.isWhitespace() }
            .lowercase(Locale.ROOT)
        require(normalized.matches(Regex("[0-9a-f]{64}"))) {
            "Invalid SHA-256 certificate fingerprint"
        }

        val preferences = context.applicationContext
            .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        check(
            preferences.edit()
                .putString("tv_fingerprint_${hostKey(host)}", normalized)
                .commit()
        ) { "Unable to persist paired TV certificate fingerprint" }
    }

    fun loadTvFingerprint(context: Context, host: String): String? {
        val preferences = context.applicationContext
            .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        return loadTvFingerprint(preferences, host, hostKey(host), legacyHostKey(host))
    }

    private fun loadTvFingerprint(
        preferences: SharedPreferences,
        host: String,
        id: String,
        legacyId: String
    ): String? {
        val current = preferences.getString("tv_fingerprint_$id", null)
        if (current != null) return current

        val legacy = preferences.getString("tv_fingerprint_$legacyId", null) ?: return null
        preferences.edit()
            .putString("tv_fingerprint_$id", legacy)
            .commit()
        return legacy
    }

    fun hasPairedTv(context: Context, host: String): Boolean =
        loadTvFingerprint(context, host) != null
}