/*
 * Copyright © 2026 RTAkland
 * Author: RTAkland
 * Date: 2026-10-02
 */

@file:OptIn(ExperimentalForeignApi::class)

package cn.rtast.webrtc

import cn.rtast.webrtc.state.RTCDataChannelState
import kotlinx.cinterop.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import libdatachannel.*
import platform.posix.fprintf
import platform.posix.memcpy
import platform.posix.stderr
import kotlin.concurrent.Volatile

public actual class RTCDataChannel internal constructor(
    private val dc: Int,
    public actual val label: String,
    private val peer: RTCPeerConnection,
    scope: CoroutineScope,
) {
    private val nativeEvents = Channel<NativeDatachannelEvent>(Channel.UNLIMITED)

    private val _state = MutableStateFlow(RTCDataChannelState.Connecting)
    private val _bufferedAmount = MutableStateFlow(0L)
    private val _bufferedAmountLow = Channel<Unit>(Channel.CONFLATED)
    private val _textMessages = MutableSharedFlow<String>(0, 64, BufferOverflow.SUSPEND)
    private val _binaryMessages = MutableSharedFlow<ByteArray>(0, 64, BufferOverflow.SUSPEND)

    public actual val state: StateFlow<RTCDataChannelState> = _state.asStateFlow()
    public actual val bufferedAmount: StateFlow<Long> = _bufferedAmount.asStateFlow()
    public actual val bufferedAmountLow: Flow<Unit> = _bufferedAmountLow.receiveAsFlow()
    public actual val messages: MessageFlow = MessageFlow()

    @Volatile
    private var cleanedUp = false
    private val selfRef = StableRef.create(this)

    public actual val protocol: String
        get() = if (isClosed) "" else memScoped {
            val buf = allocArray<ByteVar>(256)
            val r = rtcGetDataChannelProtocol(dc, buf, 256)
            if (r < 0) "" else buf.toKString()
        }

    public actual val isOpen: Boolean get() = _state.value == RTCDataChannelState.Open
    public actual val isClosed: Boolean get() = _state.value == RTCDataChannelState.Closed
    public actual val availableAmount: Int
        get() = if (isClosed) 0 else rtcGetAvailableAmount(dc).coerceAtLeast(0)

    init {
        rtcSetUserPointer(dc, selfRef.asCPointer())
        rtcSetOpenCallback(dc, openCallback)
        rtcSetClosedCallback(dc, closeCallback)
        rtcSetMessageCallback(dc, messageCallback)
        rtcSetBufferedAmountLowCallback(dc, bufferedAmountLowCallback)
        rtcSetErrorCallback(dc, errorCallback)

        _state.value = when {
            rtcIsClosed(dc) -> RTCDataChannelState.Closed
            rtcIsOpen(dc) -> RTCDataChannelState.Open
            else -> RTCDataChannelState.Connecting
        }
        _bufferedAmount.value = rtcGetBufferedAmount(dc).toLong()
        scope.launch { consumeNativeEvents() }
    }

    private suspend fun consumeNativeEvents() {
        try {
            for (event in nativeEvents) {
                when (event) {
                    NativeDatachannelEvent.Open -> {
                        _state.value = RTCDataChannelState.Open
                    }

                    NativeDatachannelEvent.Closing -> {
                        _state.value = RTCDataChannelState.Closing
                    }

                    NativeDatachannelEvent.Closed -> {
                        _state.value = RTCDataChannelState.Closed
                        doCleanup()
                        return
                    }

                    is NativeDatachannelEvent.TextMessage -> {
                        _textMessages.emit(event.text)
                        refreshBufferedAmount()
                    }

                    is NativeDatachannelEvent.BinaryMessage -> {
                        _binaryMessages.emit(event.data)
                        refreshBufferedAmount()
                    }

                    NativeDatachannelEvent.BufferedLow -> {
                        _bufferedAmountLow.trySend(Unit)
                        refreshBufferedAmount()
                    }

                    is NativeDatachannelEvent.Error -> handleError(event.message)
                }
            }
        } finally {
            doCleanup()
        }
    }

    private fun refreshBufferedAmount() {
        _bufferedAmount.value = rtcGetBufferedAmount(dc).toLong()
    }

    private fun handleError(message: String) {
        if (isClosed || _state.value == RTCDataChannelState.Closed) {
            reportError(message, fatal = false)
            return
        }
        reportError(message, fatal = true)
        _state.value = RTCDataChannelState.Closing
        rtcClose(dc)
    }

    private fun reportError(message: String, fatal: Boolean) {
        val handler = RTCPeerConnectionFactory.errorHandler
        if (handler != null) handler(RTCException(message, fatal))
        else fprintf(stderr, "[kotlin-webrtc] datachannel error: %s\n", message)
    }

    private fun doCleanup() {
        if (cleanedUp) return
        cleanedUp = true
        rtcDelete(dc)
        peer.removeChannel(dc)
        _state.value = RTCDataChannelState.Closed
        nativeEvents.close()
        _bufferedAmountLow.close()
        selfRef.dispose()
    }

    public actual fun setBufferedAmountLowThreshold(threshold: Int): Boolean {
        if (isClosed) return false
        rtcSetBufferedAmountLowThreshold(dc, threshold)
        return true
    }

    public actual fun send(text: String): Boolean {
        if (isClosed) return false
        memScoped { rtcSendMessage(dc, text.cstr.ptr, -1) }
        refreshBufferedAmount()
        return true
    }

    public actual fun send(data: ByteArray): Boolean {
        if (isClosed || data.isEmpty()) return false
        data.usePinned { pinned -> rtcSendMessage(dc, pinned.addressOf(0), data.size) }
        refreshBufferedAmount()
        return true
    }

    public actual fun close(): Boolean {
        if (isClosed) return false
        _state.value = RTCDataChannelState.Closing
        rtcClose(dc)
        return true
    }

    public actual inner class MessageFlow internal actual constructor() {
        public actual val text: SharedFlow<String> = _textMessages.asSharedFlow()
        public actual val bytes: SharedFlow<ByteArray> = _binaryMessages.asSharedFlow()
    }

    companion {
        private val openCallback = staticCFunction<Int, COpaquePointer?, Unit> { _, user ->
            val self = user?.asStableRef<RTCDataChannel>()?.get() ?: return@staticCFunction
            self.nativeEvents.trySend(NativeDatachannelEvent.Open)
        }

        private val closeCallback = staticCFunction<Int, COpaquePointer?, Unit> { _, user ->
            val self = user?.asStableRef<RTCDataChannel>()?.get() ?: return@staticCFunction
            self.nativeEvents.trySend(NativeDatachannelEvent.Closed)
        }

        private val messageCallback =
            staticCFunction<Int, CPointer<ByteVar>?, Int, COpaquePointer?, Unit> { _, msg, size, user ->
                val self = user?.asStableRef<RTCDataChannel>()?.get() ?: return@staticCFunction
                if (size > 0 && msg != null) {
                    val bytes = ByteArray(size)
                    // Do NOT use readBytes!!!
                    bytes.usePinned { pinned -> memcpy(pinned.addressOf(0), msg, size.convert()) }
                    self.nativeEvents.trySend(NativeDatachannelEvent.BinaryMessage(bytes))
                } else {
                    self.nativeEvents.trySend(NativeDatachannelEvent.TextMessage(msg?.toKString() ?: ""))
                }
            }

        private val bufferedAmountLowCallback =
            staticCFunction<Int, COpaquePointer?, Unit> { _, user ->
                val self = user?.asStableRef<RTCDataChannel>()?.get() ?: return@staticCFunction
                self.nativeEvents.trySend(NativeDatachannelEvent.BufferedLow)
            }

        private val errorCallback =
            staticCFunction<Int, CPointer<ByteVar>?, COpaquePointer?, Unit> { _, error, user ->
                val self = user?.asStableRef<RTCDataChannel>()?.get() ?: return@staticCFunction
                self.nativeEvents.trySend(
                    NativeDatachannelEvent.Error(error?.toKString() ?: "unknown error")
                )
            }
    }
}