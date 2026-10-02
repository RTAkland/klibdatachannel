/*
 * Copyright © 2026 RTAkland
 * Author: RTAkland
 * Date: 2026-10-02
 */

@file:OptIn(ExperimentalForeignApi::class)

package cn.rtast.webrtc.configuration

import kotlinx.cinterop.ExperimentalForeignApi
import libdatachannel.*

internal fun RTCLogLevel.toNative(): UInt = when( this) {
    RTCLogLevel.DISABLED -> RTC_LOG_NONE
    RTCLogLevel.FATAL -> RTC_LOG_FATAL
    RTCLogLevel.ERROR -> RTC_LOG_ERROR
    RTCLogLevel.WARNING -> RTC_LOG_WARNING
    RTCLogLevel.INFO -> RTC_LOG_INFO
    RTCLogLevel.DEBUG -> RTC_LOG_DEBUG
    RTCLogLevel.VERBOSE ->RTC_LOG_VERBOSE
}