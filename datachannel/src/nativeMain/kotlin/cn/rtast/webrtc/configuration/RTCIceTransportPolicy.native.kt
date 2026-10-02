/*
 * Copyright © 2026 RTAkland
 * Author: RTAkland
 * Date: 2026-10-02
 */

package cn.rtast.webrtc.configuration

internal fun RTCIceTransportPolicy.toNative() = when (this) {
    RTCIceTransportPolicy.ALL -> 0u
    RTCIceTransportPolicy.RELAY -> 1u
}