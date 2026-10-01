/*
 * Copyright © 2026 RTAkland
 * Author: RTAkland
 * Date: 2026-09-29
 */


package cn.rtast.webrtc.configuration

/**
 * Type of certificate used to secure the DTLS handshake
 * libdatachannel generates a self-signed certificate for each
 * [cn.rtast.webrtc.RTCPeerConnection] and uses it to establish the DTLS session
 *
 * @property value The unsigned int value passed to the native libdatachannel API
 */
public enum class RTCCertificateType(public val value: UInt) {
    DEFAULT(0u),
    ECDSA(1u),
    RSA(2u),
}