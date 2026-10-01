/*
 * Copyright © 2026 RTAkland
 * Author: RTAkland
 * Date: 2026-10-01
 */


package cn.rtast.webrtc.state

public enum class RTCGatheringState(public val value: Int) {
    NEW(0),
    IN_PROGRESS(1),
    COMPLETE(2);

    public companion object {
        public fun from(v: Int): RTCGatheringState =
            entries.firstOrNull { it.value == v } ?: NEW
    }
}