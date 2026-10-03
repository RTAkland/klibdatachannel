/*
 * Copyright © 2026 RTAkland
 * Author: RTAkland
 * Date: 2026-10-03
 */


package cn.rtast.webrtc.configuration

public data class RTCIceServer(
    public val urls: List<String>,
    public val username: String? = null,
    public val credential: String? = null,
) {
    public constructor(
        url: String,
        username: String? = null,
        credential: String? = null,
    ) : this(listOf(url), username, credential)

    init {
        require(urls.isNotEmpty()) { "urls must not be empty" }
        require(urls.all { it.isNotBlank() }) { "urls must not contain blank entries" }
        require((username == null) == (credential == null)) {
            "username and credential must be provided together"
        }
    }

    override fun toString(): String {
        if (username == null || credential == null) return urls.first()
        return urls.joinToString(",") { url ->
            val schemeEnd = url.indexOf(':')
            require(schemeEnd > 0) { "Invalid ICE server url: $url" }
            val scheme = url.substring(0, schemeEnd)
            val rest = url.substring(schemeEnd + 1)
            "$scheme:${percentEncode(username)}:${percentEncode(credential)}@$rest"
        }
    }

    companion {
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