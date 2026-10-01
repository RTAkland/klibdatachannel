/*
 * Copyright © 2026 RTAkland
 * Author: RTAkland
 * Date: 2026-10-01
 */


package cn.rtast.webrtc.configuration

public class RTCDataChannelConfig internal constructor(
    public val ordered: Boolean,
    public val maxRetransmits: Int?,
    public val maxPacketLifeTime: Int?,
    public val protocol: String,
    public val negotiated: Boolean,
) {
    init {
        require(maxRetransmits == null || maxPacketLifeTime == null) {
            "maxRetransmits and maxPacketLifeTime are mutually exclusive"
        }
        require(maxRetransmits == null || maxRetransmits >= 0) {
            "maxRetransmits must be >= 0"
        }
        require(maxPacketLifeTime == null || maxPacketLifeTime >= 0) {
            "maxPacketLifeTime must be >= 0"
        }
    }

    public companion object {
        public val Reliable: RTCDataChannelConfig = RTCDataChannelConfig(
            true, null, null, "", false
        )
    }
}

public fun rtcDataChannelConfig(block: RTCDataChannelConfigBuilder.() -> Unit): RTCDataChannelConfig {
    val builder = RTCDataChannelConfigBuilder()
    builder.block()
    return builder.build()
}

public class RTCDataChannelConfigBuilder {
    public var ordered: Boolean = true
    public var maxRetransmits: Int? = null
    public var maxPacketLifeTime: Int? = null
    public var protocol: String = ""
    public var negotiated: Boolean = false
    public fun maxRetransmits(n: Int) {
        maxRetransmits = n
        maxPacketLifeTime = null
    }

    public fun maxPacketLifeTime(millis: Int) {
        maxPacketLifeTime = millis
        maxRetransmits = null
    }

    public fun unordered() {
        ordered = false
    }

    internal fun build(): RTCDataChannelConfig = RTCDataChannelConfig(
        ordered, maxRetransmits, maxPacketLifeTime, protocol, negotiated
    )
}