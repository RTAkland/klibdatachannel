/*
 * Copyright © 2026 RTAkland
 * Author: RTAkland
 * Date: 2026-09-29
 */

@file:OptIn(ExperimentalForeignApi::class)

package cn.rtast.webrtc

import cn.rtast.webrtc.state.RTCDataChannelState
import kotlinx.cinterop.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import libdatachannel.*
import kotlin.concurrent.Volatile

public class RTCDataChannel internal constructor(
    private val dc: Int,
    public val label: String,
    private val peer: RTCPeerConnection,
    private val scope: CoroutineScope,
) {
    private val nativeEvents = Channel<NativeDatachannelEvent>(Channel.UNLIMITED)
    private val outgoing = Channel<Any>(Channel.UNLIMITED)
    private val _state = MutableStateFlow(RTCDataChannelState.Connecting)
    private val _textMessages = MutableSharedFlow<String>(0, 64, BufferOverflow.SUSPEND)
    private val _binaryMessages = MutableSharedFlow<ByteArray>(0, 64, BufferOverflow.SUSPEND)
    private val _bufferedAmount = MutableStateFlow(0L)
    private val _bufferedAmountLow = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    public val state: StateFlow<RTCDataChannelState> = _state.asStateFlow()
    public val textMessages: SharedFlow<String> = _textMessages.asSharedFlow()
    public val binaryMessages: SharedFlow<ByteArray> = _binaryMessages.asSharedFlow()
    public val messages: MessageFlow = MessageFlow()
    public val bufferedAmount: StateFlow<Long> = _bufferedAmount.asStateFlow()
    public val bufferedAmountLow: SharedFlow<Unit> = _bufferedAmountLow.asSharedFlow()

    @Volatile
    private var cleanedUp = false
    private val selfRef = StableRef.create(this)


    public val protocol: String
        get() = if (isClosed) "" else memScoped {
            val buf = allocArray<ByteVar>(256)
            val r = rtcGetDataChannelProtocol(dc, buf, 256)
            if (r < 0) "" else buf.toKString()
        }

    public val isOpen: Boolean get() = _state.value == RTCDataChannelState.Open
    public val isClosed: Boolean get() = _state.value == RTCDataChannelState.Closed
    public val availableAmount: Int
        get() = if (isClosed) 0 else rtcGetAvailableAmount(dc).coerceAtLeast(0)

    /**
     * Async sending api
     */
    public val async: AsyncDataChannel = AsyncDataChannel()

    init {
        rtcSetUserPointer(dc, selfRef.asCPointer())
        rtcSetOpenCallback(dc, openCallback)
        rtcSetClosedCallback(dc, closeCallback)
        rtcSetMessageCallback(dc, messageCallback)
        rtcSetBufferedAmountLowCallback(dc, bufferedAmountLowCallback)

        _state.value = when {
            rtcIsClosed(dc) -> RTCDataChannelState.Closed
            rtcIsOpen(dc) -> RTCDataChannelState.Open
            else -> RTCDataChannelState.Connecting
        }
        _bufferedAmount.value = rtcGetBufferedAmount(dc).toLong()
        scope.launch { consumeNativeEvents() }
        scope.launch { consumeOutgoingMessages() }
    }

    private suspend fun consumeOutgoingMessages() {
        for (msg in outgoing) {
            state.first { it == RTCDataChannelState.Open || it == RTCDataChannelState.Closed }
            if (isClosed) break
            when (msg) {
                is ByteArray -> if (msg.isNotEmpty()) msg.usePinned { rtcSendMessage(dc, it.addressOf(0), msg.size) }
                is String -> memScoped { rtcSendMessage(dc, msg.cstr.ptr, -1) }
            }
        }
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
                        _bufferedAmount.value = rtcGetBufferedAmount(dc).toLong()
                    }

                    is NativeDatachannelEvent.BinaryMessage -> {
                        _binaryMessages.emit(event.data)
                        _bufferedAmount.value = rtcGetBufferedAmount(dc).toLong()
                    }

                    NativeDatachannelEvent.BufferedLow -> {
                        _bufferedAmountLow.emit(Unit)
                        _bufferedAmount.value = rtcGetBufferedAmount(dc).toLong()
                    }
                }
            }
        } finally {
            if (!cleanedUp) doCleanup()
        }
    }

    private fun doCleanup() {
        if (cleanedUp) return
        cleanedUp = true
        rtcDelete(dc)
        peer.removeChannel(dc)
        _state.value = RTCDataChannelState.Closed
        nativeEvents.close()
        selfRef.dispose()
    }

    public fun setBufferedAmountLowThreshold(threshold: Int) {
        if (isClosed) return
        rtcSetBufferedAmountLowThreshold(dc, threshold)
    }

    /**
     * Sending a [String] when channel is opened
     */
    public fun send(text: String): Unit = run { outgoing.trySend(text) }

    /**
     * Sending a [ByteArray] when channel is opened
     */
    public fun send(data: ByteArray): Unit = run { outgoing.trySend(data) }

    public fun close() {
        if (isClosed) return
        _state.value = RTCDataChannelState.Closing
        rtcClose(dc)
    }

    /**
     * Async api for sending [String] or [ByteArray], unordered.
     */
    public inner class AsyncDataChannel internal constructor() {
        public fun send(text: String): Job = scope.launch { this@RTCDataChannel.send(text) }
        public fun send(data: ByteArray): Job = scope.launch { this@RTCDataChannel.send(data) }
    }

    public inner class MessageFlow internal constructor() {
        public val text: SharedFlow<String> = _textMessages.asSharedFlow()
        public val bytes: SharedFlow<ByteArray> = _binaryMessages.asSharedFlow()
    }

    private interface NativeDatachannelEvent {
        object Open : NativeDatachannelEvent
        object Closing : NativeDatachannelEvent
        object Closed : NativeDatachannelEvent
        value class TextMessage(val text: String) : NativeDatachannelEvent
        value class BinaryMessage(val data: ByteArray) : NativeDatachannelEvent
        object BufferedLow : NativeDatachannelEvent
    }

    public companion object {
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
                if (size > 0) self.nativeEvents.trySend(
                    NativeDatachannelEvent.BinaryMessage(
                        msg?.readBytes(size) ?: ByteArray(0)
                    )
                ) else self.nativeEvents.trySend(NativeDatachannelEvent.TextMessage(msg?.toKString() ?: ""))
            }

        private val bufferedAmountLowCallback =
            staticCFunction<Int, COpaquePointer?, Unit> { _, user ->
                val self = user?.asStableRef<RTCDataChannel>()?.get() ?: return@staticCFunction
                self.nativeEvents.trySend(NativeDatachannelEvent.BufferedLow)
            }
    }
}