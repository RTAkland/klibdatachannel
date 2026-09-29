/*
 * Copyright © 2026 RTAkland
 * Author: RTAkland
 * Date: 2026-09-28
 */


package cn.rtast.webrtc

public enum class RTCConnectionState(public val value: Int) {
    NEW(0),
    CONNECTING(1),
    CONNECTED(2),
    DISCONNECTED(3),
    FAILED(4),
    CLOSED(5);

    public companion object {
        public fun from(v: Int): RTCConnectionState = entries.firstOrNull { it.value == v } ?: NEW
    }
}