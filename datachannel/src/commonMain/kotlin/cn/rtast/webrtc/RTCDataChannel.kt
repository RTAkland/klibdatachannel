/*
 * Copyright © 2026 RTAkland
 * Author: RTAkland
 * Date: 2026-09-29
 */

@file:OptIn(ExperimentalForeignApi::class)

package cn.rtast.webrtc

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.cinterop.*
import libdatachannel.*
import kotlin.concurrent.Volatile

public class RTCDataChannel internal constructor(
    private val dc: Int,
    public val label: String,
    private val peer: RTCPeerConnection,
) {
    @Volatile
    private var closed = false

    @Volatile
    private var cleanedUp = false

    @Volatile
    private var openedPending = false

    @Volatile
    private var closedPending = false
    private val lock = SynchronizedObject()
    private var onOpen: (() -> Unit)? = null
        set(value) {
            synchronized(lock) {
                field = value
                if (value != null && openedPending) {
                    openedPending = false
                    value.invoke()
                }
            }
        }

    private var onClose: (() -> Unit)? = null
        set(value) {
            synchronized(lock) {
                field = value
                if (value != null && closedPending) {
                    closedPending = false
                    value.invoke()
                }
            }
        }

    private var onMessage: ((RTCDataChannelMessage) -> Unit)? = null
    private var onBufferedAmountLow: (() -> Unit)? = null
    private val selfRef = StableRef.create(this)

    public val bufferedAmount: Long
        get() = if (closed) 0L else rtcGetBufferedAmount(dc).toLong()

    public val protocol: String
        get() {
            if (closed) return ""
            return memScoped {
                val buf = allocArray<ByteVar>(256)
                val r = rtcGetDataChannelProtocol(dc, buf, 256)
                if (r < 0) "" else buf.toKString()
            }
        }

    public val reliability: RTCDataChannelReliability
        get() {
            if (closed) return RTCDataChannelReliability.Reliable
            return memScoped {
                val rel = alloc<rtcReliability>()
                val r = rtcGetDataChannelReliability(dc, rel.ptr)
                if (r < 0) return@memScoped RTCDataChannelReliability.Reliable
                when {
                    rel.unordered && rel.unreliable && rel.maxRetransmits > 0u ->
                        RTCDataChannelReliability.MaxRetransmitsUnordered(rel.maxRetransmits.toInt())

                    rel.unordered && rel.unreliable && rel.maxPacketLifeTime > 0u ->
                        RTCDataChannelReliability.MaxPacketLifeTimeUnordered(rel.maxPacketLifeTime.toInt())

                    rel.unreliable && rel.maxRetransmits > 0u ->
                        RTCDataChannelReliability.MaxRetransmits(rel.maxRetransmits.toInt())

                    rel.unreliable && rel.maxPacketLifeTime > 0u -> RTCDataChannelReliability.MaxPacketLifeTime(rel.maxPacketLifeTime.toInt())

                    rel.unordered -> RTCDataChannelReliability.ReliableUnordered
                    else -> RTCDataChannelReliability.Reliable
                }
            }
        }

    public val isOpen: Boolean get() = !closed && rtcIsOpen(dc)
    public val isClosed: Boolean get() = closed || rtcIsClosed(dc)

    public val availableAmount: Int
        get() = if (closed) 0 else rtcGetAvailableAmount(dc).coerceAtLeast(0)


    init {
        val user = selfRef.asCPointer()
        rtcSetUserPointer(dc, user)
        rtcSetOpenCallback(dc, openCallback)
        rtcSetClosedCallback(dc, closeCallback)
        rtcSetMessageCallback(dc, msgCallback)
        rtcSetBufferedAmountLowCallback(dc, bufferedAmountLowCallback)
        if (rtcIsOpen(dc)) openedPending = true
        if (rtcIsClosed(dc)) closedPending = true
    }

    public fun onBufferedAmountLow(block: () -> Unit) {
        this.onBufferedAmountLow = block
    }

    public fun setBufferedAmountLowThreshold(threshold: Int) {
        if (closed) return
        rtcSetBufferedAmountLowThreshold(dc, threshold)
    }

    public fun onMessage(block: (RTCDataChannelMessage) -> Unit) {
        this.onMessage = block
    }

    public fun onClose(block: () -> Unit) {
        this.onClose = block
    }

    public fun onOpen(block: () -> Unit) {
        this.onOpen = block
    }

    public fun sendText(msg: String) {
        if (closed) return
        memScoped { rtcSendMessage(dc, msg.cstr.ptr, -1) }
    }

    public fun sendBinary(data: ByteArray) {
        if (closed || data.isEmpty()) return
        data.usePinned { pinned -> rtcSendMessage(dc, pinned.addressOf(0), data.size) }
    }

    public fun close() {
        if (closed) return
        closed = true
        rtcClose(dc)
    }

    public companion object {
        private val openCallback = staticCFunction<Int, COpaquePointer?, Unit> { _, user ->
            val self = user?.asStableRef<RTCDataChannel>()?.get() ?: return@staticCFunction
            val cb = self.onOpen
            if (cb != null) {
                cb.invoke()
            } else self.openedPending = true
        }

        private val closeCallback = staticCFunction<Int, COpaquePointer?, Unit> { _, user ->
            val self = user?.asStableRef<RTCDataChannel>()?.get() ?: return@staticCFunction
            if (self.cleanedUp) return@staticCFunction
            self.cleanedUp = true
            self.closed = true
            val cb = self.onClose
            if (cb != null) cb.invoke() else self.closedPending = true
            rtcDelete(self.dc)
            self.peer.removeChannel(self.dc)
            self.selfRef.dispose()
        }

        private val msgCallback =
            staticCFunction<Int, CPointer<ByteVar>?, Int, COpaquePointer?, Unit> { _, msg, size, user ->
                val self = user?.asStableRef<RTCDataChannel>()?.get() ?: return@staticCFunction
                if (size > 0) {
                    val bytes = msg?.readBytes(size) ?: ByteArray(0)
                    self.onMessage?.invoke(RTCDataChannelMessage.Binary(bytes))
                } else self.onMessage?.invoke(RTCDataChannelMessage.Text(msg?.toKString() ?: ""))
            }

        private val bufferedAmountLowCallback =
            staticCFunction<Int, COpaquePointer?, Unit> { _, user ->
                val self = user?.asStableRef<RTCDataChannel>()?.get() ?: return@staticCFunction
                self.onBufferedAmountLow?.invoke()
            }
    }
}