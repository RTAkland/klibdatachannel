/*
 * Copyright © 2026 RTAkland
 * Author: RTAkland
 * Date: 2026-09-28
 */

package cn.rtast.webrtc

import cn.rtast.webrtc.configuration.RTCConfiguration
import cn.rtast.webrtc.configuration.RTCDataChannelConfig
import cn.rtast.webrtc.configuration.RTCIceTransportType
import cn.rtast.webrtc.state.RTCConnectionState
import cn.rtast.webrtc.state.RTCGatheringState
import cn.rtast.webrtc.state.RTCIceState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * @param parentScope scope whose Job becomes the parent of a `peerconnection`
 * @param iceConfig ICE/transport configuration
 */
public expect class RTCPeerConnection internal constructor(
    parentScope: CoroutineScope, iceConfig: RTCConfiguration,
) {
    public constructor(scope: CoroutineScope)

    public val connectionState: StateFlow<RTCConnectionState>
    public val iceState: StateFlow<RTCIceState>
    public val gatheringState: StateFlow<RTCGatheringState>
    public val selectedCandidatePair: StateFlow<RTCSelectedCandidate?>
    public val localDescriptions: SharedFlow<RTCSessionDescription>
    public val localCandidates: SharedFlow<RTCCandidate>
    public val incomingDataChannels: SharedFlow<RTCDataChannel>

    public val localSessionDescription: RTCSessionDescription?
    public val remoteSessionDescription: RTCSessionDescription?

    public val isClosed: Boolean

    public fun selectedIceTransportType(): RTCIceTransportType
    public fun createOffer()
    public fun createAnswer()
    public fun setRemoteDescription(sdp: String, type: String)
    public fun addRemoteCandidate(candidate: String, mid: String)
    public fun addRemoteCandidate(candidate: RTCCandidate)
    public fun createDataChannel(
        label: String,
        protocol: String,
        config: RTCDataChannelConfig = RTCDataChannelConfig.Reliable,
    ): RTCDataChannel

    public fun createDataChannel(label: String): RTCDataChannel
    public fun createDataChannel(label: String, config: RTCDataChannelConfig): RTCDataChannel
    public fun close()
}