/*
 * Copyright © 2026 RTAkland
 * Author: RTAkland
 * Date: 2026-10-01
 */


package cn.rtast.webrtc.configuration

/**
 * Configuration for an RTC datachannel
 *
 * @property ordered whether messages are delivered in order
 * @property maxRetransmits max retransmission attempts, or null for unlimited
 * @property maxPacketLifeTime max time in ms to retransmit, or null for unlimited
 * @property protocol subprotocol name, empty for none
 * @property negotiated whether the channel is negotiated out-of-band
 */
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

    companion {
        /**
         * Default reliable, ordered channel
         */
        @Suppress("PropertyName")
        public val Reliable: RTCDataChannelConfig = RTCDataChannelConfig(true, null, null, "", false)
    }
}

public fun rtcDataChannelConfig(block: RTCDataChannelConfigBuilder.() -> Unit): RTCDataChannelConfig {
    val builder = RTCDataChannelConfigBuilder()
    builder.block()
    return builder.build()
}

/** Mutable builder for [RTCDataChannelConfig]. */
public class RTCDataChannelConfigBuilder {
    public var ordered: Boolean = true
    public var maxRetransmits: Int? = null
    public var maxPacketLifeTime: Int? = null
    public var protocol: String = ""
    public var negotiated: Boolean = false

    /**
     * Sets retransmit limit
     * clears [maxPacketLifeTime]
     */
    public fun maxRetransmits(n: Int) {
        maxRetransmits = n
        maxPacketLifeTime = null
    }

    /**
     * Sets packet lifetime
     * clears [maxRetransmits]
     */
    public fun maxPacketLifeTime(millis: Int) {
        maxPacketLifeTime = millis
        maxRetransmits = null
    }

    /**
     * Marks the channel as unordered
     */
    public fun unordered() {
        ordered = false
    }

    internal fun build(): RTCDataChannelConfig = RTCDataChannelConfig(
        ordered, maxRetransmits, maxPacketLifeTime, protocol, negotiated
    )
}