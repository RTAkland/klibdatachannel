/*
 * Copyright © 2026 RTAkland
 * Author: RTAkland
 * Date: 2026-09-29
 */


package cn.rtast.webrtc

/**
 * Transport protocol used to communicate with a TURN server
 * The protocol is encoded into the TURN URL as the `transport` query parameter
 *
 * @property value The string value used in the TURN URL query parameter
 */
public enum class RTCTransport(public val value: String) {
    UDP("udp"),
    TCP("tcp"),
}