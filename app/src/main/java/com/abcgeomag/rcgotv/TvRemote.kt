package com.abcgeomag.rcgotv

import android.os.Build
import android.os.Handler
import android.os.Looper
import remote.RemoteAppLinkLaunchRequest
import remote.RemoteConfigure
import remote.RemoteDeviceInfo
import remote.RemoteEditInfo
import remote.RemoteImeBatchEdit
import remote.RemoteImeKeyInject
import remote.RemoteImeObject
import remote.RemoteKeyCode
import remote.RemoteKeyInject
import remote.RemoteMessage
import remote.RemotePingResponse
import remote.RemoteSetActive
import java.io.IOException
import java.net.InetSocketAddress
import javax.net.ssl.SSLSocket
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class TvRemote(
    private val host: String,
    private val id: ClientIdentity,
    private val onReady: () -> Unit,
    private val onError: (Throwable) -> Unit,
    onTextStateChanged: (Boolean) -> Unit = {}
) {
    companion object {
        private const val REQUESTED_FEATURES = 622
        private const val MAX_RECONNECT_ATTEMPTS = 5
        private const val INITIAL_RECONNECT_DELAY_MS = 1_000L
        private const val MAX_RECONNECT_DELAY_MS = 15_000L
    }

    private val lock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var textStateListener: ((Boolean) -> Unit)? = onTextStateChanged

    private var socket: SSLSocket? = null
    private var socketGeneration = -1L
    private var socketSession = -1L

    private var activeFeatures = REQUESTED_FEATURES
    private var handshakeReady = false
    private var configureSent = false
    private var activeSent = false
    private var imeCounter = 0
    private var fieldCounter = 0

    private var generation = 0L
    private var sessionSequence = 0L
    private var explicitlyStopped = true
    private var running = false
    private var connectionJob: Job? = null
    private var ioScope: CoroutineScope? = null

    fun setTextStateListener(listener: ((Boolean) -> Unit)?) {
        textStateListener = listener
    }

    fun start() {
        synchronized(lock) {
            if (running && connectionJob?.isActive == true) {
                return
            }

            generation++
            explicitlyStopped = false
            running = true

            val thisGeneration = generation
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            ioScope = scope
            connectionJob = scope.launch {
                runConnectionLoop(thisGeneration)
            }
        }
    }

    private suspend fun runConnectionLoop(thisGeneration: Long) {
        var reconnectAttempt = 0

        while (isGenerationActive(thisGeneration)) {
            val thisSession = synchronized(lock) {
                if (generation != thisGeneration || explicitlyStopped) {
                    return
                }
                sessionSequence++
                handshakeReady = false
                activeFeatures = REQUESTED_FEATURES
                configureSent = false
                activeSent = false
                imeCounter = 0
                fieldCounter = 0
                sessionSequence
            }

            postForSession(thisGeneration, thisSession) {
                textStateListener?.invoke(false)
            }

            var failure: Throwable? = null

            try {
                val fingerprint = id.tvFingerprint
                val context = if (fingerprint.isNullOrBlank()) {
                    Tls.context(id)
                } else {
                    Tls.authenticatedContext(id, fingerprint)
                }

                val newSocket = context.socketFactory.createSocket() as SSLSocket
                newSocket.useClientMode = true

                val published = synchronized(lock) {
                    if (generation == thisGeneration &&
                        sessionSequence == thisSession &&
                        !explicitlyStopped
                    ) {
                        socket = newSocket
                        socketGeneration = thisGeneration
                        socketSession = thisSession
                        true
                    } else {
                        false
                    }
                }

                if (!published) {
                    closeQuietly(newSocket)
                    break
                }

                newSocket.connect(InetSocketAddress(host, 6466), 8_000)
                newSocket.startHandshake()

                while (isSessionActive(thisGeneration, thisSession)) {
                    val frame = Framing.read(newSocket.inputStream)
                    val message = RemoteMessage.parseFrom(frame)

                    when {
                        message.hasRemoteConfigure() -> {
                            val configurePayload = synchronized(lock) {
                                if (generation != thisGeneration ||
                                    sessionSequence != thisSession ||
                                    explicitlyStopped ||
                                    configureSent
                                ) {
                                    null
                                } else {
                                    activeFeatures = REQUESTED_FEATURES
                                    configureSent = true
                                    config(REQUESTED_FEATURES)
                                }
                            }

                            if (configurePayload != null) {
                                send(thisGeneration, thisSession, configurePayload)
                            }
                        }

                        message.hasRemoteSetActive() -> {
                            val activePayload = synchronized(lock) {
                                if (generation != thisGeneration ||
                                    sessionSequence != thisSession ||
                                    explicitlyStopped ||
                                    activeSent
                                ) {
                                    null
                                } else {
                                    activeSent = true
                                    RemoteMessage.newBuilder().setRemoteSetActive(
                                        RemoteSetActive.newBuilder()
                                            .setActive(REQUESTED_FEATURES)
                                    ).build().toByteArray()
                                }
                            }

                            if (activePayload != null) {
                                send(thisGeneration, thisSession, activePayload)
                            }
                        }

                        message.hasRemoteStart() -> {
                            if (message.remoteStart.started &&
                                markHandshakeReady(thisGeneration, thisSession)
                            ) {
                                reconnectAttempt = 0
                                postReady(thisGeneration, thisSession)
                            }
                        }

                        message.hasRemotePingRequest() -> {
                            val pingPayload =
                                RemoteMessage.newBuilder().setRemotePingResponse(
                                    RemotePingResponse.newBuilder()
                                        .setVal1(1)
                                ).build().toByteArray()
                            send(thisGeneration, thisSession, pingPayload)
                        }

                        message.hasRemoteImeBatchEdit() -> {
                            val edit = message.remoteImeBatchEdit
                            val updated = synchronized(lock) {
                                if (generation == thisGeneration &&
                                    sessionSequence == thisSession &&
                                    !explicitlyStopped
                                ) {
                                    imeCounter = edit.imeCounter
                                    fieldCounter = edit.fieldCounter
                                    true
                                } else {
                                    false
                                }
                            }
                            if (updated) {
                                postForSession(thisGeneration, thisSession) {
                                    textStateListener?.invoke(true)
                                }
                            }
                        }

                        message.hasRemoteImeKeyInject() -> {
                            val state = message.remoteImeKeyInject
                            if (state.hasTextFieldStatus()) {
                                val updated = synchronized(lock) {
                                    if (generation == thisGeneration &&
                                        sessionSequence == thisSession &&
                                        !explicitlyStopped
                                    ) {
                                        fieldCounter = state.textFieldStatus.counterField
                                        true
                                    } else {
                                        false
                                    }
                                }
                                if (updated) {
                                    postForSession(thisGeneration, thisSession) {
                                        textStateListener?.invoke(true)
                                    }
                                }
                            }
                        }

                        message.hasRemoteImeShowRequest() -> {
                            val state = message.remoteImeShowRequest
                            if (state.hasRemoteTextFieldStatus()) {
                                val updated = synchronized(lock) {
                                    if (generation == thisGeneration &&
                                        sessionSequence == thisSession &&
                                        !explicitlyStopped
                                    ) {
                                        fieldCounter =
                                            state.remoteTextFieldStatus.counterField
                                        true
                                    } else {
                                        false
                                    }
                                }
                                if (updated) {
                                    postForSession(thisGeneration, thisSession) {
                                        textStateListener?.invoke(true)
                                    }
                                }
                            }
                        }

                        message.hasRemoteError() -> {
                            val remoteError = message.remoteError
                            val detail = "TV returned a remote protocol error: " +
                                message.remoteError.toString()
                            postForSession(thisGeneration, thisSession) {
                                onError(IllegalStateException(detail))
                            }
                        }
                    }
                }

                if (isGenerationActive(thisGeneration)) {
                    failure = IOException("Remote connection ended")
                }
            } catch (e: CancellationException) {
                break
            } catch (t: Throwable) {
                if (!isGenerationActive(thisGeneration)) {
                    break
                }
                failure = t
            } finally {
                synchronized(lock) {
                    if (generation == thisGeneration &&
                        sessionSequence == thisSession
                    ) {
                        handshakeReady = false
                        imeCounter = 0
                        fieldCounter = 0
                    }
                }
                postForSession(thisGeneration, thisSession) {
                    textStateListener?.invoke(false)
                }
                closeSocket(thisGeneration, thisSession)
            }

            if (!isGenerationActive(thisGeneration)) {
                break
            }

            reconnectAttempt++
            if (reconnectAttempt > MAX_RECONNECT_ATTEMPTS) {
                val error = IllegalStateException(
                    "Remote connection lost after $MAX_RECONNECT_ATTEMPTS reconnect attempts",
                    failure
                )
                postForGeneration(thisGeneration) {
                    onError(error)
                }
                synchronized(lock) {
                    if (generation == thisGeneration && !explicitlyStopped) {
                        running = false
                    }
                }
                break
            }

            val delayMs = (
                INITIAL_RECONNECT_DELAY_MS shl (reconnectAttempt - 1).coerceAtMost(4)
            ).coerceAtMost(MAX_RECONNECT_DELAY_MS)

            try {
                delay(delayMs)
            } catch (_: CancellationException) {
                break
            }
        }

        synchronized(lock) {
            if (generation == thisGeneration && !explicitlyStopped) {
                running = false
            }
        }
    }

    private fun markHandshakeReady(thisGeneration: Long, thisSession: Long): Boolean =
        synchronized(lock) {
            if (generation == thisGeneration &&
                sessionSequence == thisSession &&
                !explicitlyStopped &&
                !handshakeReady
            ) {
                handshakeReady = true
                true
            } else {
                false
            }
        }

    private fun isGenerationActive(thisGeneration: Long): Boolean =
        synchronized(lock) {
            generation == thisGeneration && !explicitlyStopped
        }

    private fun isSessionActive(thisGeneration: Long, thisSession: Long): Boolean =
        synchronized(lock) {
            generation == thisGeneration &&
                sessionSequence == thisSession &&
                !explicitlyStopped
        }

    private fun postReady(thisGeneration: Long, thisSession: Long) {
        mainHandler.post {
            val valid = synchronized(lock) {
                generation == thisGeneration &&
                    sessionSequence == thisSession &&
                    !explicitlyStopped &&
                    handshakeReady
            }
            if (valid) {
                onReady()
            }
        }
    }

    private fun postForGeneration(thisGeneration: Long, block: () -> Unit) {
        mainHandler.post {
            val valid = synchronized(lock) {
                generation == thisGeneration && !explicitlyStopped
            }
            if (valid) {
                block()
            }
        }
    }

    private fun postForSession(
        thisGeneration: Long,
        thisSession: Long,
        block: () -> Unit
    ) {
        mainHandler.post {
            val valid = synchronized(lock) {
                generation == thisGeneration &&
                    sessionSequence == thisSession &&
                    !explicitlyStopped
            }
            if (valid) {
                block()
            }
        }
    }

    private fun closeSocket(thisGeneration: Long, thisSession: Long) {
        val current = synchronized(lock) {
            if (socketGeneration == thisGeneration &&
                socketSession == thisSession
            ) {
                val value = socket
                socket = null
                socketGeneration = -1L
                socketSession = -1L
                value
            } else {
                null
            }
        }
        closeQuietly(current)
    }

    private fun closeQuietly(current: SSLSocket?) {
        try {
            current?.close()
        } catch (_: Throwable) {
        }
    }

    private fun config(features: Int) = RemoteMessage.newBuilder().setRemoteConfigure(
        RemoteConfigure.newBuilder().setCode1(features).setDeviceInfo(
            RemoteDeviceInfo.newBuilder()
                .setModel(Build.MODEL)
                .setVendor(Build.MANUFACTURER)
                .setUnknown1(1)
                .setUnknown2("1")
                .setPackageName("androitv-remote")
                .setAppVersion("1.0.0")
        )
    ).build().toByteArray()

    private fun rawKeyCode(keyCode: Int) {
        val request = synchronized(lock) {
            if (!handshakeReady || explicitlyStopped || !running) {
                null
            } else {
                Triple(generation, sessionSequence, ioScope)
            }
        } ?: return

        request.third?.launch {
            try {
                send(
                    request.first,
                    request.second,
                    RemoteMessage.newBuilder().setRemoteKeyInject(
                        RemoteKeyInject.newBuilder()
                            .setKeyCode(keyCode)
                            .setDirection(RemoteKeyInject.Direction.SHORT)
                    ).build().toByteArray()
                )
            } catch (t: Throwable) {
                abortSession(request.first, request.second)
            }
        }
    }

    fun key(key: RemoteKeyCode.KeyCode) {
        val request = synchronized(lock) {
            if (!handshakeReady || explicitlyStopped || !running) {
                null
            } else {
                Triple(generation, sessionSequence, ioScope)
            }
        } ?: return

        request.third?.launch {
            try {
                send(
                    request.first,
                    request.second,
                    RemoteMessage.newBuilder().setRemoteKeyInject(
                        RemoteKeyInject.newBuilder()
                            .setKeyCode(key.number)
                            .setDirection(RemoteKeyInject.Direction.SHORT)
                    ).build().toByteArray()
                )
            } catch (t: Throwable) {
                abortSession(request.first, request.second)
            }
        }
    }

    fun sendText(text: String) {
        val request = synchronized(lock) {
            if (!handshakeReady || explicitlyStopped || !running) {
                null
            } else {
                Triple(
                    generation,
                    sessionSequence,
                    Triple(imeCounter, fieldCounter, ioScope)
                )
            }
        } ?: return

        val thisGeneration = request.first
        val thisSession = request.second
        val ime = request.third.first
        val field = request.third.second

        request.third.third?.launch {
            try {
                val position = text.length.coerceAtLeast(1) - 1
                send(
                    thisGeneration,
                    thisSession,
                    RemoteMessage.newBuilder().setRemoteImeBatchEdit(
                        RemoteImeBatchEdit.newBuilder()
                            .setImeCounter(ime)
                            .setFieldCounter(field)
                            .addEditInfo(
                                RemoteEditInfo.newBuilder()
                                    .setInsert(1)
                                    .setTextFieldStatus(
                                        RemoteImeObject.newBuilder()
                                            .setStart(position)
                                            .setEnd(position)
                                            .setValue(text)
                                    )
                            )
                    ).build().toByteArray()
                )
            } catch (t: Throwable) {
                abortSession(thisGeneration, thisSession)
            }
        }
    }

    private fun abortSession(thisGeneration: Long, thisSession: Long) {
        val current = synchronized(lock) {
            if (generation == thisGeneration &&
                sessionSequence == thisSession &&
                socketGeneration == thisGeneration &&
                socketSession == thisSession
            ) {
                val value = socket
                socket = null
                socketGeneration = -1L
                socketSession = -1L
                value
            } else {
                null
            }
        }
        closeQuietly(current)
    }

    private fun send(
        thisGeneration: Long,
        thisSession: Long,
        bytes: ByteArray
    ) {
        synchronized(lock) {
            if (generation != thisGeneration ||
                sessionSequence != thisSession ||
                explicitlyStopped ||
                socketGeneration != thisGeneration ||
                socketSession != thisSession
            ) {
                throw IOException("Remote socket is not connected")
            }

            val current = socket ?: throw IOException("Remote socket is not connected")
            Framing.write(current.outputStream, bytes)
        }
    }

    fun launchAppLink(appLink: String) {
        val link = appLink.trim()
        if (link.isEmpty()) return
        val request = synchronized(lock) {
            if (!handshakeReady || explicitlyStopped || !running) {
                null
            } else {
                Triple(generation, sessionSequence, ioScope)
            }
        } ?: return

        request.third?.launch {
            try {
                send(
                    request.first,
                    request.second,
                    RemoteMessage.newBuilder()
                        .setRemoteAppLinkLaunchRequest(
                            RemoteAppLinkLaunchRequest.newBuilder()
                                .setAppLink(link)
                        )
                        .build()
                        .toByteArray()
                )
            } catch (t: Throwable) {
                abortSession(request.first, request.second)
            }
        }
    }

    fun clearText() {
        sendText("")
    }

    fun power() = key(RemoteKeyCode.KeyCode.KEYCODE_POWER)
    fun home() = key(RemoteKeyCode.KeyCode.KEYCODE_HOME)
    fun back() = key(RemoteKeyCode.KeyCode.KEYCODE_BACK)
    fun up() = key(RemoteKeyCode.KeyCode.KEYCODE_DPAD_UP)
    fun down() = key(RemoteKeyCode.KeyCode.KEYCODE_DPAD_DOWN)
    fun left() = key(RemoteKeyCode.KeyCode.KEYCODE_DPAD_LEFT)
    fun right() = key(RemoteKeyCode.KeyCode.KEYCODE_DPAD_RIGHT)
    fun ok() = key(RemoteKeyCode.KeyCode.KEYCODE_DPAD_CENTER)
    fun volumeUp() = key(RemoteKeyCode.KeyCode.KEYCODE_VOLUME_UP)
    fun volumeDown() = key(RemoteKeyCode.KeyCode.KEYCODE_VOLUME_DOWN)
    fun mute() = key(RemoteKeyCode.KeyCode.KEYCODE_MUTE)
    fun channelUp() = rawKeyCode(166)
    fun channelDown() = rawKeyCode(167)
    fun menu() = rawKeyCode(82)
    fun input() = rawKeyCode(178)
    fun number(number: Int) {
        val code = when (number) {
            0 -> 7
            in 1..9 -> number + 7
            else -> return
        }
        rawKeyCode(code)
    }
    fun star() = rawKeyCode(17)
    fun pound() = rawKeyCode(18)
    fun playPause() = key(RemoteKeyCode.KeyCode.KEYCODE_MEDIA_PLAY_PAUSE)
    fun enter() = key(RemoteKeyCode.KeyCode.KEYCODE_ENTER)
    fun delete() = key(RemoteKeyCode.KeyCode.KEYCODE_DEL)
    fun space() = key(RemoteKeyCode.KeyCode.KEYCODE_SPACE)

    fun stop() {
        val resources = synchronized(lock) {
            generation++
            explicitlyStopped = true
            running = false

            val job = connectionJob
            connectionJob = null

            val scope = ioScope
            ioScope = null

            val current = socket
            socket = null
            socketGeneration = -1L
            socketSession = -1L

            Triple(job, scope, current)
        }

        resources.first?.cancel()
        resources.second?.cancel()
        closeQuietly(resources.third)
    }
}