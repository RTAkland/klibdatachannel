/*
 * Copyright © 2026 RTAkland
 * Author: RTAkland
 * Date: 2026-09-29
 */

package cn.rtast.webrtc.configuration


public data class RTCConfiguration(
    public val iceServers: List<String>,
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
    private val iceServers = mutableListOf<String>()

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
    public fun stun(host: String, port: Int = 3478, transport: RTCTransport = RTCTransport.UDP) {
        iceServers += "stun:$host:$port?transport=${transport.value}"
    }

    /**
     * TURN server, [tls] should never set to `true`,
     * because libjuice is not supported
     */
    public fun turn(
        host: String,
        port: Int = 3478,
        username: String,
        password: String,
        transport: RTCTransport = RTCTransport.UDP,
        tls: Boolean = false,
    ) {
        val scheme = if (tls) "turns" else "turn"
        val auth = "${percentEncode(username)}:${percentEncode(password)}"
        val transportStr = if (tls) "tcp" else transport.value
        iceServers += "$scheme:$auth@$host:$port?transport=$transportStr"
    }

    /**
     * RAW ice server url
     * @sample stun: stun.l.google.com:3478?transport=udp
     */
    public fun raw(url: String) {
        iceServers += url
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

    internal companion object {
        private const val HEX = "0123456789ABCDEF"

        private fun percentEncode(s: String): String {
            val sb = StringBuilder()
            for (b in s.encodeToByteArray()) {
                val v = b.toInt() and 0xFF
                val c = v.toChar()
                if ((c in 'a'..'z') || (c in 'A'..'Z') || (c in '0'..'9') || c in "-_.~") sb.append(c)
                else {
                    sb.append('%')
                    sb.append(HEX[v shr 4])
                    sb.append(HEX[v and 0x0F])
                }
            }
            return sb.toString()
        }
    }
}