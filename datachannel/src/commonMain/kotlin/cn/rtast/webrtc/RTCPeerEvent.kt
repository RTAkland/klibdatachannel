/*
 * Copyright © 2026 RTAkland
 * Author: RTAkland
 * Date: 2026-09-28
 */


package cn.rtast.webrtc

public sealed class RTCPeerEvent {
    public data class LocalDescription(val sdp: String, val type: String) : RTCPeerEvent()
    public data class LocalCandidate(val candidate: String, val mid: String) : RTCPeerEvent()
    public data class State(val state: RTCConnectionState) : RTCPeerEvent()
    public data class IncomingChannel(val channel: RTCDataChannel) : RTCPeerEvent()
}