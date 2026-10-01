/*
 * Copyright © 2026 RTAkland
 * Author: RTAkland
 * Date: 2026-09-29
 */

@file:OptIn(ExperimentalForeignApi::class)

package cn.rtast.webrtc

import cn.rtast.webrtc.state.RTCDataChannelState
import kotlinx.cinterop.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import libdatachannel.*
import kotlin.concurrent.Volatile

public class RTCDataChannel internal constructor(
    private val dc: Int,
    public val label: String,
    private val peer: RTCPeerConnection,
    parentScope: CoroutineScope,
) {
    private val nativeEvents = Channel<NativeDatachannelEvent>(Channel.UNLIMITED)
    private val dcScope = CoroutineScope(
        parentScope.coroutineContext +
                SupervisorJob(parentScope.coroutineContext[Job]) +
                Dispatchers.Default
    )

    private val _state = MutableStateFlow(RTCDataChannelState.Connecting)
    public val state: StateFlow<RTCDataChannelState> = _state.asStateFlow()

    private val _messages = MutableSharedFlow<RTCDataChannelMessage>(0, 64, BufferOverflow.SUSPEND)
    public val messages: SharedFlow<RTCDataChannelMessage> = _messages.asSharedFlow()

    private val _bufferedAmount = MutableStateFlow(0L)
    public val bufferedAmount: StateFlow<Long> = _bufferedAmount.asStateFlow()

    private val _bufferedAmountLow = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    public val bufferedAmountLow: SharedFlow<Unit> = _bufferedAmountLow.asSharedFlow()

    private val selfRef = StableRef.create(this)

    @Volatile
    private var cleanedUp = false

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

    init {
        rtcSetUserPointer(dc, selfRef.asCPointer())
        rtcSetOpenCallback(dc, openCallback)
        rtcSetClosedCallback(dc, closeCallback)
        rtcSetMessageCallback(dc, msgCallback)
        rtcSetBufferedAmountLowCallback(dc, bufferedAmountLowCallback)

        _state.value = when {
            rtcIsClosed(dc) -> RTCDataChannelState.Closed
            rtcIsOpen(dc) -> RTCDataChannelState.Open
            else -> RTCDataChannelState.Connecting
        }
        _bufferedAmount.value = rtcGetBufferedAmount(dc).toLong()

        dcScope.launch { consumeNativeEvents() }
    }

    private suspend fun consumeNativeEvents() {
        try {
            for (e in nativeEvents) {
                when (e) {
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

                    is NativeDatachannelEvent.Message -> {
                        _messages.emit(e.msg)
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

    public fun send(msg: String) {
        if (isClosed) return
        memScoped { rtcSendMessage(dc, msg.cstr.ptr, -1) }
    }

    public fun send(data: ByteArray) {
        if (isClosed || data.isEmpty()) return
        data.usePinned { pinned -> rtcSendMessage(dc, pinned.addressOf(0), data.size) }
    }

    public fun send(message: RTCDataChannelMessage): Unit = when (message) {
        is RTCDataChannelMessage.Text -> send(message.value)
        is RTCDataChannelMessage.Binary -> send(message.value)
    }

    public suspend fun sendTextSuspending(msg: String) {
        state.first { it == RTCDataChannelState.Open || it == RTCDataChannelState.Closed }
        if (isClosed) return
        send(msg)
    }

    public suspend fun sendBinarySuspending(data: ByteArray) {
        state.first { it == RTCDataChannelState.Open || it == RTCDataChannelState.Closed }
        if (isClosed) return
        send(data)
    }

    public fun close() {
        if (isClosed) return
        _state.value = RTCDataChannelState.Closing
        rtcClose(dc)
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

        private val msgCallback =
            staticCFunction<Int, CPointer<ByteVar>?, Int, COpaquePointer?, Unit> { _, msg, size, user ->
                val self = user?.asStableRef<RTCDataChannel>()?.get() ?: return@staticCFunction
                val message = if (size > 0) RTCDataChannelMessage.Binary(msg?.readBytes(size) ?: ByteArray(0))
                else RTCDataChannelMessage.Text(msg?.toKString() ?: "")
                self.nativeEvents.trySend(NativeDatachannelEvent.Message(message))
            }

        private val bufferedAmountLowCallback =
            staticCFunction<Int, COpaquePointer?, Unit> { _, user ->
                val self = user?.asStableRef<RTCDataChannel>()?.get() ?: return@staticCFunction
                self.nativeEvents.trySend(NativeDatachannelEvent.BufferedLow)
            }
    }
}

private sealed interface NativeDatachannelEvent {
    data object Open : NativeDatachannelEvent
    data object Closing : NativeDatachannelEvent
    data object Closed : NativeDatachannelEvent
    data class Message(val msg: RTCDataChannelMessage) : NativeDatachannelEvent
    data object BufferedLow : NativeDatachannelEvent
}