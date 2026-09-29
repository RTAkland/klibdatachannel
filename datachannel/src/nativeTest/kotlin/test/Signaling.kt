/*
 * Copyright © 2026 RTAkland
 * Author: RTAkland
 * Date: 2026-09-30
 */


package test

interface Signaling {
    fun sendSdp(sdp: String, type: String)
    fun sendCandidate(candidate: String, mid: String)
    fun onSdp(handler: (sdp: String, type: String) -> Unit)
    fun onCandidate(handler: (candidate: String, mid: String) -> Unit)
}

class LoopbackSignaling : Signaling {
    private var sdpHandler: ((String, String) -> Unit)? = null
    private var candHandler: ((String, String) -> Unit)? = null
    var peer: LoopbackSignaling? = null

    override fun sendSdp(sdp: String, type: String) {
        peer?.sdpHandler?.invoke(sdp, type)
    }

    override fun sendCandidate(candidate: String, mid: String) {
        peer?.candHandler?.invoke(candidate, mid)
    }

    override fun onSdp(handler: (String, String) -> Unit) {
        sdpHandler = handler
    }

    override fun onCandidate(handler: (String, String) -> Unit) {
        candHandler = handler
    }
}