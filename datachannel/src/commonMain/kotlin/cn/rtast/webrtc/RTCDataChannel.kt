/*
 * Copyright © 2026 RTAkland
 * Author: RTAkland
 * Date: 2026-09-29
 */

@file:OptIn(ExperimentalForeignApi::class)

package cn.rtast.webrtc

import kotlinx.cinterop.*
import libdatachannel.*
import kotlin.concurrent.Volatile

public class RTCDataChannel internal constructor(
    private val dc: Int,
    public val label: String,
    private val peer: RTCPeerConnection,
) {
    private var onOpen: (() -> Unit)? = null
        set(value) {
            field = value
            if (value != null && openedPending) {
                openedPending = false
                value.invoke()
            }
        }

    private var onClose: (() -> Unit)? = null
        set(value) {
            field = value
            if (value != null && closedPending) {
                closedPending = false
                value.invoke()
            }
        }

    private var onMessage: ((RTCDataChannelMessage) -> Unit)? = null
    private val selfRef = StableRef.create(this)

    @Volatile
    private var closed = false

    @Volatile
    private var openedPending = false

    @Volatile
    private var closedPending = false

    init {
        val user = selfRef.asCPointer()
        rtcSetUserPointer(dc, user)
        rtcSetOpenCallback(dc, openCb)
        rtcSetClosedCallback(dc, closeCb)
        rtcSetMessageCallback(dc, msgCb)
        if (rtcIsOpen(dc)) openedPending = true
        if (rtcIsClosed(dc)) closedPending = true
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
        rtcDelete(dc)
        selfRef.dispose()
        peer.removeChannel(dc)
    }

    public companion object {
        private val openCb = staticCFunction<Int, COpaquePointer?, Unit> { _, user ->
            val self = user?.asStableRef<RTCDataChannel>()?.get() ?: return@staticCFunction
            val cb = self.onOpen
            if (cb != null) {
                cb.invoke()
            } else self.openedPending = true
        }

        private val closeCb = staticCFunction<Int, COpaquePointer?, Unit> { _, user ->
            val self = user?.asStableRef<RTCDataChannel>()?.get() ?: return@staticCFunction
            val cb = self.onClose
            if (cb != null) {
                cb.invoke()
            } else self.closedPending = true
        }

        private val msgCb = staticCFunction<Int, CPointer<ByteVar>?, Int, COpaquePointer?, Unit> { _, msg, size, user ->
            val self = user?.asStableRef<RTCDataChannel>()?.get() ?: return@staticCFunction
            if (size > 0) {
                val bytes = msg?.readBytes(size) ?: ByteArray(0)
                self.onMessage?.invoke(RTCDataChannelMessage.Binary(bytes))
            } else self.onMessage?.invoke(RTCDataChannelMessage.Text(msg?.toKString() ?: ""))
        }
    }
}