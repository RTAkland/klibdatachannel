/*
 * Copyright © 2026 RTAkland
 * Author: RTAkland
 * Date: 2026-10-02
 */

@file:OptIn(ExperimentalForeignApi::class)

package cn.rtast.webrtc

import cn.rtast.webrtc.configuration.RTCConfiguration
import cn.rtast.webrtc.configuration.RTCConfigurationBuilder
import cn.rtast.webrtc.configuration.RTCLogLevel
import cn.rtast.webrtc.configuration.rtcConfiguration
import cn.rtast.webrtc.configuration.toNative
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import libdatachannel.LIBDATACHANNEL_VERSION
import libdatachannel.rtcCleanup
import libdatachannel.rtcInitLogger
import libdatachannel.rtcPreload

public actual class RTCPeerConnectionFactory actual constructor(logLevel: RTCLogLevel) {
    public actual fun createPeerConnection(
        scope: CoroutineScope,
        config: RTCConfiguration,
    ): RTCPeerConnection = check(initialized) { "Call WebRTC.init() first" }.let { RTCPeerConnection(scope, config) }

    public actual fun createPeerConnection(
        scope: CoroutineScope,
        config: RTCConfigurationBuilder.() -> Unit,
    ): RTCPeerConnection = createPeerConnection(scope, rtcConfiguration(config))

    public actual fun onError(handler: (RTCException) -> Unit) {
        errorHandler = handler
    }

    public actual fun cleanup() {
        if (!initialized) return
        rtcCleanup()
        initialized = false
    }

    public companion object {
        internal var errorHandler: ((RTCException) -> Unit)? = null
        private var initialized = false
    }

    init {
        if (!initialized) {
            rtcInitLogger(logLevel.toNative(), null)
            rtcPreload()
            initialized = true
        }
    }
}

public actual val RTCPeerConnectionFactory.VERSION: String
    get() = LIBDATACHANNEL_VERSION