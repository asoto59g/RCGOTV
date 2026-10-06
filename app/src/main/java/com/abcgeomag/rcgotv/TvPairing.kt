package com.abcgeomag.rcgotv

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.google.protobuf.ByteString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pairing.PairingConfiguration
import pairing.PairingEncoding
import pairing.PairingMessage
import pairing.PairingOption
import pairing.PairingRequest
import pairing.PairingSecret
import pairing.RoleType
import java.math.BigInteger
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import javax.net.ssl.SSLSocket

class TvPairing(
    private val context: Context,
    private val host: String,
    private val onCode: () -> Unit,
    private val onPaired: (ClientIdentity) -> Unit,
    private val onError: (Throwable) -> Unit
) {
    private class Generation {
        val writeLock = Any()
        var socket: SSLSocket? = null
        var clientIdentity: ClientIdentity? = null
        var serverCertificate: X509Certificate? = null
        var ready = false
        var cancelled = false
        var finished = false
        var pairedCallbackScheduled = false
    }

    private val lock = Any()
    private var currentGeneration: Generation? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    fun start() {
        val generation = synchronized(lock) {
            val current = currentGeneration
            if (current != null && !current.cancelled && !current.finished) {
                return
            }
            Generation().also { currentGeneration = it }
        }

        Thread {
            try {
                if (!isActive(generation)) return@Thread

                val id = CertificateStore.loadOrCreate(context, host)
                synchronized(lock) {
                    if (!isActiveLocked(generation)) return@Thread
                    generation.clientIdentity = id
                }

                val s = Tls.pairingContext(id).socketFactory.createSocket() as SSLSocket
                val published = synchronized(lock) {
                    if (isActiveLocked(generation)) {
                        generation.socket = s
                        true
                    } else {
                        false
                    }
                }
                if (!published) {
                    try {
                        s.close()
                    } catch (_: Throwable) {
                    }
                    return@Thread
                }

                s.connect(InetSocketAddress(host, 6467), 8000)
                if (!isActive(generation)) return@Thread

                s.useClientMode = true
                s.startHandshake()

                val serverCertificate = s.session.peerCertificates.firstOrNull() as? X509Certificate
                    ?: throw IllegalStateException("TV did not provide an X.509 certificate")

                synchronized(lock) {
                    if (!isActiveLocked(generation)) return@Thread
                    generation.serverCertificate = serverCertificate
                }

                send(generation, request())

                while (isActive(generation)) {
                    val frame = Framing.read(s.inputStream)
                    if (!isActive(generation)) return@Thread
                    val message = PairingMessage.parseFrom(frame)

                    when {
                        message.status == PairingMessage.Status.STATUS_BAD_SECRET ->
                            throw IllegalStateException("TV rejected the pairing secret")

                        message.status != PairingMessage.Status.STATUS_OK ->
                            throw IllegalStateException("TV status: " + message.status)

                        message.hasPairingRequestAck() ->
                            send(generation, option())

                        message.hasPairingOption() ->
                            send(generation, config())

                        message.hasPairingConfigurationAck() -> {
                            synchronized(lock) {
                                if (!isActiveLocked(generation)) return@Thread
                                generation.ready = true
                            }
                            postCallback(generation, onCode)
                        }

                        message.hasPairingSecretAck() -> {
                            if (completePairing(generation, id, serverCertificate)) {
                                return@Thread
                            }
                            return@Thread
                        }
                    }
                }
            } catch (t: Throwable) {
                failGeneration(generation, t)
            }
        }.start()
    }

    private fun isActive(generation: Generation): Boolean =
        synchronized(lock) { isActiveLocked(generation) }

    private fun isActiveLocked(generation: Generation): Boolean =
        currentGeneration === generation && !generation.cancelled && !generation.finished

    private fun postCallback(generation: Generation, callback: () -> Unit) {
        mainHandler.post {
            synchronized(lock) {
                if (currentGeneration === generation && !generation.cancelled) {
                    callback()
                }
            }
        }
    }

    private fun completePairing(
        generation: Generation,
        identity: ClientIdentity,
        certificate: X509Certificate
    ): Boolean {
        if (!isActive(generation)) return false

        CertificateStore.saveTvFingerprint(context, host, certificate)

        val socketToClose = synchronized(lock) {
            if (!isActiveLocked(generation) || generation.pairedCallbackScheduled) {
                return false
            }
            generation.pairedCallbackScheduled = true
            generation.finished = true
            generation.socket.also { generation.socket = null }
        }

        try {
            socketToClose?.close()
        } catch (_: Throwable) {
        }

        postCallback(generation) { onPaired(identity) }
        return true
    }

    private fun failGeneration(generation: Generation, error: Throwable) {
        val socketToClose = synchronized(lock) {
            if (!isActiveLocked(generation)) return
            generation.finished = true
            generation.socket.also { generation.socket = null }
        }

        try {
            socketToClose?.close()
        } catch (_: Throwable) {
        }

        postCallback(generation) { onError(error) }
    }

    private fun request() =
        PairingMessage.newBuilder()
            .setProtocolVersion(2)
            .setStatus(PairingMessage.Status.STATUS_OK)
            .setPairingRequest(
                PairingRequest.newBuilder()
                    .setServiceName("androidtv-remote")
                    .setClientName(Build.MODEL.ifBlank { "RCGOTV" })
                    .build()
            )
            .build()
            .toByteArray()

    private fun option() =
        PairingMessage.newBuilder()
            .setProtocolVersion(2)
            .setStatus(PairingMessage.Status.STATUS_OK)
            .setPairingOption(
                PairingOption.newBuilder()
                    .setPreferredRole(RoleType.ROLE_TYPE_INPUT)
                    .addInputEncodings(
                        PairingEncoding.newBuilder()
                            .setType(PairingEncoding.EncodingType.ENCODING_TYPE_HEXADECIMAL)
                            .setSymbolLength(6)
                    )
                    .build()
            )
            .build()
            .toByteArray()

    private fun config() =
        PairingMessage.newBuilder()
            .setProtocolVersion(2)
            .setStatus(PairingMessage.Status.STATUS_OK)
            .setPairingConfiguration(
                PairingConfiguration.newBuilder()
                    .setClientRole(RoleType.ROLE_TYPE_INPUT)
                    .setEncoding(
                        PairingEncoding.newBuilder()
                            .setType(PairingEncoding.EncodingType.ENCODING_TYPE_HEXADECIMAL)
                            .setSymbolLength(6)
                    )
                    .build()
            )
            .build()
            .toByteArray()

    private fun send(generation: Generation, bytes: ByteArray) {
        synchronized(generation.writeLock) {
            val output = synchronized(lock) {
                if (!isActiveLocked(generation)) return
                generation.socket?.outputStream
                    ?: throw IllegalStateException("Pairing socket is not available")
            }
            Framing.write(output, bytes)
        }
    }

    suspend fun submitCode(raw: String): Boolean = withContext(Dispatchers.IO) {
        val code = raw.trim()
            .removePrefix("0x")
            .removePrefix("0X")
            .uppercase()

        if (code.length != 6 || code.any { it !in "0123456789ABCDEF" }) {
            return@withContext false
        }

        val state = synchronized(lock) {
            val generation = currentGeneration
            if (generation == null ||
                !isActiveLocked(generation) ||
                !generation.ready ||
                generation.socket == null
            ) {
                null
            } else {
                Triple(generation, generation.clientIdentity, generation.serverCertificate)
            }
        } ?: return@withContext false

        val (generation, id, server) = state
        if (id == null || server == null) return@withContext false

        try {
            val clientKey = id.cert.publicKey as? RSAPublicKey
                ?: throw IllegalStateException("Client certificate is not RSA")
            val serverKey = server.publicKey as? RSAPublicKey
                ?: throw IllegalStateException("TV certificate is not RSA")

            fun unsigned(n: BigInteger): ByteArray {
                val bytes = n.abs().toByteArray()
                return if (bytes.size > 1 && bytes[0].toInt() == 0) {
                    bytes.copyOfRange(1, bytes.size)
                } else {
                    bytes
                }
            }

            fun exponentBytes(key: RSAPublicKey): ByteArray {
                val exponent = unsigned(key.publicExponent)
                return exponent
            }

            val pinBytes = code.substring(2)
                .chunked(2)
                .map { it.toInt(16).toByte() }
                .toByteArray()

            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(unsigned(clientKey.modulus))
            digest.update(exponentBytes(clientKey))
            digest.update(unsigned(serverKey.modulus))
            digest.update(exponentBytes(serverKey))
            digest.update(pinBytes)
            val secret = digest.digest()

            val expectedFirstByte = code.substring(0, 2).toInt(16)
            if ((secret[0].toInt() and 0xFF) != expectedFirstByte) {
                val error = IllegalArgumentException("Pairing code does not match the TLS certificates")
                postErrorIfActive(generation, error)
                return@withContext false
            }

            send(
                generation,
                PairingMessage.newBuilder()
                    .setProtocolVersion(2)
                    .setStatus(PairingMessage.Status.STATUS_OK)
                    .setPairingSecret(
                        PairingSecret.newBuilder()
                            .setSecret(ByteString.copyFrom(secret))
                            .build()
                    )
                    .build()
                    .toByteArray()
            )

            isActive(generation)
        } catch (t: Throwable) {
            postErrorIfActive(generation, t)
            false
        }
    }

    private fun postErrorIfActive(generation: Generation, error: Throwable) {
        if (isActive(generation)) {
            postCallback(generation) { onError(error) }
        }
    }

    fun stop() {
        val socketToClose = synchronized(lock) {
            val generation = currentGeneration ?: return
            if (generation.cancelled) return
            generation.cancelled = true
            generation.socket.also { generation.socket = null }
        }

        try {
            socketToClose?.close()
        } catch (_: Throwable) {
        }
    }
}