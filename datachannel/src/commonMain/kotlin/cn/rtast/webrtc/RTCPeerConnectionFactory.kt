/*
 * Copyright © 2026 RTAkland
 * Author: RTAkland
 * Date: 2026-09-28
 */


@file:OptIn(ExperimentalForeignApi::class)

package cn.rtast.webrtc

import cn.rtast.webrtc.configuration.RTCConfiguration
import cn.rtast.webrtc.configuration.RTCConfigurationBuilder
import cn.rtast.webrtc.configuration.RTCLogLevel
import cn.rtast.webrtc.configuration.rtcConfiguration
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.IO
import libdatachannel.LIBDATACHANNEL_VERSION
import libdatachannel.rtcCleanup
import libdatachannel.rtcInitLogger
import libdatachannel.rtcPreload
import kotlin.concurrent.Volatile

/**
 * WebRTC init factory, should use it globally.
 * @param logLevel libdatachannel defined log level
 * @param scope A coroutine scope, an [IO] dispatcher is recommended
 */
public class RTCPeerConnectionFactory(private val logLevel: RTCLogLevel) {
    init {
        if (!initialized) {
            rtcInitLogger(logLevel.logLevel, null)
            rtcPreload()
            initialized = true
        }
    }

    public fun cleanup() {
        if (!initialized) return
        rtcCleanup()
        initialized = false
    }

    public fun createPeerConnection(
        scope: CoroutineScope,
        config: RTCConfiguration = rtcConfiguration {},
    ): RTCPeerConnection = check(initialized) { "Call WebRTC.init() first" }.let { RTCPeerConnection(scope, config) }

    public fun createPeerConnection(
        scope: CoroutineScope,
        config: RTCConfigurationBuilder.() -> Unit,
    ): RTCPeerConnection = createPeerConnection(scope, rtcConfiguration(config))

    public fun onError(handler: (RTCException) -> Unit) {
        errorHandler = handler
    }

    public companion object {
        public const val VERSION: String = LIBDATACHANNEL_VERSION

        private var initialized = false

        @Volatile
        internal var errorHandler: ((RTCException) -> Unit)? = null
    }
}