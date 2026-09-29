/*
 * Copyright © 2026 RTAkland
 * Author: RTAkland
 * Date: 2026-09-28
 */


package cn.rtast.webrtc

public sealed class RTCDataChannelMessage {
    public data class Text(val value: String) : RTCDataChannelMessage()
    public data class Binary(val value: ByteArray) : RTCDataChannelMessage() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other == null || this::class != other::class) return false
            other as Binary
            return value.contentEquals(other.value)
        }

        override fun hashCode(): Int = value.contentHashCode()
    }
}