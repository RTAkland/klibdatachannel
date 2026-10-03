/*
 * Copyright © 2026 RTAkland
 * Author: RTAkland
 * Date: 2026-10-01
 */


package cn.rtast.webrtc

public class RTCException(
    override val message: String,
    public val fatal: Boolean,
    override val cause: Throwable?,
) : RuntimeException(message, cause) {
    public constructor(message: String, fatal: Boolean) : this(message, fatal, null)
    public constructor(message: String, cause: Throwable) : this(message, false, cause)
}