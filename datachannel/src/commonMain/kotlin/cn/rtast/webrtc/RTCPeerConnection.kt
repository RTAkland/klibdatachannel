/*
 * Copyright © 2026 RTAkland
 * Author: RTAkland
 * Date: 2026-09-28
 */

package cn.rtast.webrtc

import cn.rtast.webrtc.configuration.RTCConfiguration
import cn.rtast.webrtc.configuration.RTCDataChannelConfig
import cn.rtast.webrtc.configuration.RTCIceTransportType
import cn.rtast.webrtc.configuration.rtcDataChannelConfig
import cn.rtast.webrtc.state.RTCConnectionState
import cn.rtast.webrtc.state.RTCGatheringState
import cn.rtast.webrtc.state.RTCIceState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

public expect class RTCPeerConnection internal constructor(
    parentScope: CoroutineScope, iceConfig: RTCConfiguration,
) {
    public constructor(scope: CoroutineScope)

    public val connectionState: StateFlow<RTCConnectionState>
    public val iceState: StateFlow<RTCIceState>
    public val gatheringState: StateFlow<RTCGatheringState>
    public val selectedCandidatePair: StateFlow<RTCSelectedCandidate?>
    public val localSessionDescription: RTCSessionDescription?
    public val remoteSessionDescription: RTCSessionDescription?
    public val localDescriptions: SharedFlow<RTCSessionDescription>
    public val localCandidates: SharedFlow<RTCCandidate>
    public val incomingDataChannels: SharedFlow<RTCDataChannel>

    public val isClosed: Boolean
    public val coroutineScope: CoroutineScope

    public fun selectedIceTransportType(): RTCIceTransportType
    public fun createOffer()
    public fun createAnswer()
    public fun setRemoteDescription(sdp: String, type: String)
    public fun addRemoteCandidate(candidate: RTCCandidate)
    public fun createDataChannel(label: String, protocol: String, config: RTCDataChannelConfig): RTCDataChannel
    public fun close()
}

public fun RTCPeerConnection.createDataChannel(
    label: String,
    config: RTCDataChannelConfig = RTCDataChannelConfig.Reliable,
): RTCDataChannel = createDataChannel(label, "", config)

public fun RTCPeerConnection.createDataChannel(
    label: String,
    protocol: String,
): RTCDataChannel = createDataChannel(label, protocol, rtcDataChannelConfig {})

internal sealed interface NativePeerConnectionEvent {
    class LocalDescription(val sdp: String, val type: String) : NativePeerConnectionEvent
    class LocalCandidate(val candidate: String, val mid: String) : NativePeerConnectionEvent
    class ConnectionState(val state: RTCConnectionState) : NativePeerConnectionEvent
    class IceState(val state: RTCIceState) : NativePeerConnectionEvent
    class GatheringState(val state: RTCGatheringState) : NativePeerConnectionEvent
    class IncomingChannel(val channel: RTCDataChannel) : NativePeerConnectionEvent
}
