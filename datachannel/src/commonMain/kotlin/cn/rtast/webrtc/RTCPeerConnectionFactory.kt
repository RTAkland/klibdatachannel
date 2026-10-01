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
import libdatachannel.rtcCleanup
import libdatachannel.rtcInitLogger
import libdatachannel.rtcPreload

public object RTCPeerConnectionFactory {
    private var initialized = false

    public fun init(logLevel: RTCLogLevel = RTCLogLevel.ERROR) {
        if (initialized) return
        rtcInitLogger(logLevel.logLevel, null)
        rtcPreload()
        initialized = true
    }

    public fun cleanup() {
        if (!initialized) return
        rtcCleanup()
        initialized = false
    }

    public fun createPeerConnection(scope: CoroutineScope, config: RTCConfiguration): RTCPeerConnection {
        check(initialized) { "Call WebRTC.init() first" }
        return RTCPeerConnection(scope, config)
    }

    public fun createPeerConnection(scope: CoroutineScope): RTCPeerConnection =
        createPeerConnection(scope, rtcConfiguration {})

    public fun createPeerConnection(
        scope: CoroutineScope,
        config: RTCConfigurationBuilder.() -> Unit,
    ): RTCPeerConnection = createPeerConnection(scope, rtcConfiguration(config))
}