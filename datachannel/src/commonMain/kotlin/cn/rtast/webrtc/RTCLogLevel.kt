/*
 * Copyright © 2026 RTAkland
 * Author: RTAkland
 * Date: 2026-09-29
 */


@file:OptIn(ExperimentalForeignApi::class)

package cn.rtast.webrtc

import kotlinx.cinterop.ExperimentalForeignApi
import libdatachannel.*

public enum class RTCLogLevel(public val logLevel: UInt) {
    DISABLED(RTC_LOG_NONE),
    FATAL(RTC_LOG_FATAL),
    ERROR(RTC_LOG_ERROR),
    WARNING(RTC_LOG_WARNING),
    INFO(RTC_LOG_INFO),
    DEBUG(RTC_LOG_DEBUG),
    VERBOSE(RTC_LOG_VERBOSE)
}