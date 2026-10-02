/*
 * Copyright © 2026 RTAkland
 * Author: RTAkland
 * Date: 2026-10-02
 */

@file:OptIn(ExperimentalForeignApi::class)
@file:Suppress("unused")

package cn.rtast.webrtc.state

import kotlinx.cinterop.ExperimentalForeignApi
import libdatachannel.RTC_GATHERING_COMPLETE
import libdatachannel.RTC_GATHERING_INPROGRESS
import libdatachannel.RTC_GATHERING_NEW
import libdatachannel.rtcGatheringState

internal fun RTCGatheringState.toNative(): rtcGatheringState = when (this) {
    RTCGatheringState.NEW -> RTC_GATHERING_NEW
    RTCGatheringState.IN_PROGRESS -> RTC_GATHERING_INPROGRESS
    RTCGatheringState.COMPLETE -> RTC_GATHERING_COMPLETE
}

internal fun rtcGatheringState.toRTCGatheringState(): RTCGatheringState = when (this) {
    RTC_GATHERING_NEW -> RTCGatheringState.NEW
    RTC_GATHERING_INPROGRESS -> RTCGatheringState.IN_PROGRESS
    RTC_GATHERING_COMPLETE -> RTCGatheringState.COMPLETE
    else -> RTCGatheringState.NEW
}