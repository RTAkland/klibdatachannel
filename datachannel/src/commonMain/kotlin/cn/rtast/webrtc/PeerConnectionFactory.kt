/*
 * Copyright © 2026 RTAkland
 * Author: RTAkland
 * Date: 2026-09-28
 */


@file:OptIn(ExperimentalForeignApi::class)

package cn.rtast.webrtc

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import libdatachannel.rtcCleanup
import libdatachannel.rtcInitLogger
import libdatachannel.rtcPreload

public object PeerConnectionFactory {
    private var initialized = false

    public fun init(logLevel: RTCLogLevel = RTCLogLevel.WARNING) {
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

    public fun createPeerConnection(scope: CoroutineScope): RTCPeerConnection {
        check(initialized) { "Call WebRTC.init() first" }
        return RTCPeerConnection(scope)
    }

    public fun createPeerConnection(scope: CoroutineScope, config: RTCConfiguration): RTCPeerConnection {
        check(initialized) { "Call WebRTC.init() first" }
        return RTCPeerConnection(scope, config)
    }

    public fun createPeerConnection(
        scope: CoroutineScope,
        config: RTCConfigurationBuilder.() -> Unit,
    ): RTCPeerConnection {
        return createPeerConnection(scope, rtcConfiguration(config))
    }
}