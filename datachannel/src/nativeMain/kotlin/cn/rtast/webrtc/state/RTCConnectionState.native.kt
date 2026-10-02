/*
 * Copyright © 2026 RTAkland
 * Author: RTAkland
 * Date: 2026-10-02
 */

@file:OptIn(ExperimentalForeignApi::class)
@file:Suppress("unused")

package cn.rtast.webrtc.state

import kotlinx.cinterop.ExperimentalForeignApi
import libdatachannel.*

internal fun RTCConnectionState.toNative(): rtcState = when (this) {
    RTCConnectionState.NEW -> RTC_NEW
    RTCConnectionState.CONNECTING -> RTC_CONNECTING
    RTCConnectionState.CONNECTED -> RTC_CONNECTED
    RTCConnectionState.DISCONNECTED -> RTC_DISCONNECTED
    RTCConnectionState.FAILED -> RTC_FAILED
    RTCConnectionState.CLOSED -> RTC_CLOSED
}

internal fun rtcState.toRTCConnectionState(): RTCConnectionState = when (this) {
    RTC_NEW -> RTCConnectionState.NEW
    RTC_CONNECTING -> RTCConnectionState.CONNECTING
    RTC_CONNECTED -> RTCConnectionState.CONNECTED
    RTC_DISCONNECTED -> RTCConnectionState.DISCONNECTED
    RTC_FAILED -> RTCConnectionState.FAILED
    RTC_CLOSED -> RTCConnectionState.CLOSED
    else -> RTCConnectionState.NEW
}