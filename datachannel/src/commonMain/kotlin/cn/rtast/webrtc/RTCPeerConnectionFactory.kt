/*
 * Copyright © 2026 RTAkland
 * Author: RTAkland
 * Date: 2026-09-28
 */


package cn.rtast.webrtc

import cn.rtast.webrtc.configuration.RTCConfiguration
import cn.rtast.webrtc.configuration.RTCConfigurationBuilder
import cn.rtast.webrtc.configuration.RTCLogLevel
import cn.rtast.webrtc.configuration.rtcConfiguration
import kotlinx.coroutines.CoroutineScope

public expect class RTCPeerConnectionFactory(logLevel: RTCLogLevel) {
    public fun createPeerConnection(
        scope: CoroutineScope,
        config: RTCConfiguration = rtcConfiguration {},
    ): RTCPeerConnection

    public fun createPeerConnection(
        scope: CoroutineScope,
        config: RTCConfigurationBuilder.() -> Unit,
    ): RTCPeerConnection

    public fun onError(handler: (RTCException) -> Unit)

    public fun close()

    companion {
        internal var errorHandler: ((RTCException) -> Unit)?
    }
}

public expect companion val RTCPeerConnectionFactory.VERSION: String