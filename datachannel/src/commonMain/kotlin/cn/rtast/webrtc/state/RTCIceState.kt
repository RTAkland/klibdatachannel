/*
 * Copyright © 2026 RTAkland
 * Author: RTAkland
 * Date: 2026-10-01
 */


package cn.rtast.webrtc.state

public enum class RTCIceState(public val value: Int) {
    NEW(0),
    CHECKING(1),
    CONNECTED(2),
    COMPLETED(3),
    FAILED(4),
    DISCONNECTED(5),
    CLOSED(6);

    public companion object {
        public fun from(v: Int): RTCIceState =
            entries.firstOrNull { it.value == v } ?: NEW
    }
}