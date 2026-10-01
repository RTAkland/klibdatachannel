/*
 * Copyright © 2026 RTAkland
 * Author: RTAkland
 * Date: 2026-09-28
 */


package cn.rtast.webrtc

import cn.rtast.webrtc.state.RTCConnectionState

public sealed class RTCPeerConnectionEvent {
    public data class LocalDescription(val sdp: String, val type: String) : RTCPeerConnectionEvent()
    public data class LocalCandidate(val candidate: String, val mid: String) : RTCPeerConnectionEvent()
    public data class State(val state: RTCConnectionState) : RTCPeerConnectionEvent()
    public data class IncomingChannel(val channel: RTCDataChannel) : RTCPeerConnectionEvent()
}