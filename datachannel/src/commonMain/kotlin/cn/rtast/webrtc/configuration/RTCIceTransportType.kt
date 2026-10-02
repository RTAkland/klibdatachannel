/*
 * Copyright © 2026 RTAkland
 * Author: RTAkland
 * Date: 2026-09-30
 */


package cn.rtast.webrtc.configuration

/**
 * ICE candidate transport type,
 * ordered from most to the least direct
 */
public enum class RTCIceTransportType {
    /**
     * Direct host candidate
     */
    HOST,

    /**
     * Server-reflexive candidate
     */
    SRFLX,

    /**
     * Relay candidate (traffic forwarded through a TURN server)
     */
    RELAY,

    /**
     * Unknown transport type
     */
    UNKNOWN
}