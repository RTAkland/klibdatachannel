/*
 * Copyright © 2026 RTAkland
 * Author: RTAkland
 * Date: 2026-10-02
 */

package cn.rtast.webrtc.configuration

internal fun RTCCertificateType.toNative(): UInt = when (this) {
    RTCCertificateType.DEFAULT -> 0u
    RTCCertificateType.ECDSA -> 1u
    RTCCertificateType.RSA -> 2u
}