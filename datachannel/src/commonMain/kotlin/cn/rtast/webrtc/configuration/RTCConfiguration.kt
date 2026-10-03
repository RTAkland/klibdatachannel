/*
 * Copyright © 2026 RTAkland
 * Author: RTAkland
 * Date: 2026-09-29
 */

package cn.rtast.webrtc.configuration


public data class RTCConfiguration(
    public val iceServers: List<RTCIceServer>,
    public val iceTransportPolicy: RTCIceTransportPolicy,
    public val enableIceTcp: Boolean,
    public val enableIceUdpMux: Boolean,
    public val disableAutoNegotiation: Boolean,
    public val bindAddress: String?,
    public val proxyServer: String?,
    public val portRangeBegin: Int,
    public val portRangeEnd: Int,
    public val mtu: Int,
    public val maxMessageSize: Int,
    public val certificateType: RTCCertificateType,
)

public fun rtcConfiguration(block: RTCConfigurationBuilder.() -> Unit): RTCConfiguration {
    val builder = RTCConfigurationBuilder()
    builder.block()
    return builder.build()
}

public class RTCConfigurationBuilder {
    private val iceServers = mutableListOf<RTCIceServer>()

    /**
     * ICE transport policy. Default: [RTCIceTransportPolicy.ALL]
     */
    public var iceTransportPolicy: RTCIceTransportPolicy = RTCIceTransportPolicy.ALL

    /**
     * Enable ICE over TCP candidates. Default: `false`
     */
    public var enableIceTcp: Boolean = false

    /**
     * Enable ICE UDP multiplexing. **libjuice only**. Default: `false`
     */
    public var enableIceUdpMux: Boolean = false

    /**
     * Disable automatic negotiation; caller must call `createAnswer()` explicitly. Default: `false`
     */
    public var disableAutoNegotiation: Boolean = false

    /**
     * Local bind address, `null` = all interfaces. Default: `null`
     */
    public var bindAddress: String? = null

    /**
     * Proxy server URL. **libnice only**. Default: `null`
     */
    public var proxyServer: String? = null

    /**
     * Start of the UDP port range, `0` = automatic. Default: `0`
     */
    public var portRangeBegin: Int = 0

    /**
     * End of the UDP port range, `0` = automatic. Default: `0`
     */
    public var portRangeEnd: Int = 0

    /**
     * MTU in bytes, `0` = automatic. Default: `0`
     */
    public var mtu: Int = 0

    /**
     * Max incoming message size in bytes, `0` = default. Default: `0`
     */
    public var maxMessageSize: Int = 0

    /**
     * DTLS certificate type. Default: [RTCCertificateType.DEFAULT]
     */
    public var certificateType: RTCCertificateType = RTCCertificateType.DEFAULT

    /**
     * STUN server
     */
    public fun stun(
        host: String,
        port: Int = 3478,
        transport: RTCTransport = RTCTransport.UDP,
    ) {
        iceServers += RTCIceServer("stun:$host:$port?transport=${transport.value}")
    }

    /**
     * TURN server
     */
    public fun turn(
        host: String,
        port: Int,
        username: String,
        password: String,
        transport: RTCTransport = RTCTransport.UDP,
        tls: Boolean = false,
    ) {
        val scheme = if (tls) "turns" else "turn"
        iceServers += RTCIceServer("$scheme:$host:$port?transport=${transport.value}", username, password)
    }

    /**
     * RAW ice server url
     * @sample stun: stun:stun.l.google.com:3478?transport=udp
     * @sample stun: turn:turn.cloudflare.com:3478?transport=udp
     */
    public fun ice(url: String) {
        iceServers += RTCIceServer(url, null, null)
    }

    internal fun build(): RTCConfiguration = RTCConfiguration(
        iceServers = iceServers.toList(),
        iceTransportPolicy = iceTransportPolicy,
        enableIceTcp = enableIceTcp,
        enableIceUdpMux = enableIceUdpMux,
        disableAutoNegotiation = disableAutoNegotiation,
        bindAddress = bindAddress,
        proxyServer = proxyServer,
        portRangeBegin = portRangeBegin,
        portRangeEnd = portRangeEnd,
        mtu = mtu,
        maxMessageSize = maxMessageSize,
        certificateType = certificateType,
    )
}