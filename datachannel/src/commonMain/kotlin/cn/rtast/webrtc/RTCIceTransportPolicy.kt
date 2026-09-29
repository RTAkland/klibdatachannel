/*
 * Copyright © 2026 RTAkland
 * Author: RTAkland
 * Date: 2026-09-29
 */


package cn.rtast.webrtc

/**
 * ICE transport policy that controls which candidate
 * types the ICE agent gathers and uses for connectivity checks
 */
public enum class RTCIceTransportPolicy(public val value: UInt) {
    ALL(0u),
    RELAY(1u);
}