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

internal fun RTCIceState.toNative(): rtcIceState = when (this) {
    RTCIceState.NEW -> RTC_ICE_NEW
    RTCIceState.CHECKING -> RTC_ICE_CHECKING
    RTCIceState.CONNECTED -> RTC_ICE_CONNECTED
    RTCIceState.COMPLETED -> RTC_ICE_COMPLETED
    RTCIceState.FAILED -> RTC_ICE_FAILED
    RTCIceState.DISCONNECTED -> RTC_ICE_DISCONNECTED
    RTCIceState.CLOSED -> RTC_ICE_CLOSED
}

internal fun rtcIceState.toRTCIceState(): RTCIceState = when (this) {
    RTC_ICE_NEW -> RTCIceState.NEW
    RTC_ICE_CHECKING -> RTCIceState.CHECKING
    RTC_ICE_CONNECTED -> RTCIceState.CONNECTED
    RTC_ICE_COMPLETED -> RTCIceState.COMPLETED
    RTC_ICE_FAILED -> RTCIceState.FAILED
    RTC_ICE_DISCONNECTED -> RTCIceState.DISCONNECTED
    RTC_ICE_CLOSED -> RTCIceState.CLOSED
    else -> RTCIceState.NEW
}