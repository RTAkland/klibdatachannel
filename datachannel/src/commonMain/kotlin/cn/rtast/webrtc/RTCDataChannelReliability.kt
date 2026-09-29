/*
 * Copyright © 2026 RTAkland
 * Author: RTAkland
 * Date: 2026-09-30
 */


package cn.rtast.webrtc

public sealed class RTCDataChannelReliability {
    public object Reliable : RTCDataChannelReliability()
    public object ReliableUnordered : RTCDataChannelReliability()
    public data class MaxRetransmits(val count: Int) : RTCDataChannelReliability()
    public data class MaxPacketLifeTime(val millis: Int) : RTCDataChannelReliability()
    public data class MaxRetransmitsUnordered(val count: Int) : RTCDataChannelReliability()
    public data class MaxPacketLifeTimeUnordered(val millis: Int) : RTCDataChannelReliability()
}