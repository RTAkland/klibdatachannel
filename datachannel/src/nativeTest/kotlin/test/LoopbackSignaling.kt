/*
 * Copyright © 2026 RTAkland
 * Author: RTAkland
 * Date: 2026-09-30
 */


package test

import cn.rtast.webrtc.RTCCandidate
import cn.rtast.webrtc.RTCSessionDescription
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

class LoopbackSignaling {
    var peer: LoopbackSignaling? = null

    private val sdpChannel = Channel<RTCSessionDescription>(Channel.UNLIMITED)
    private val candidateChannel = Channel<RTCCandidate>(Channel.UNLIMITED)

    val remoteDescriptions: Flow<RTCSessionDescription> = sdpChannel.receiveAsFlow()
    val remoteCandidates: Flow<RTCCandidate> = candidateChannel.receiveAsFlow()

    fun sendSdp(sd: RTCSessionDescription) {
        peer?.sdpChannel?.trySend(sd)
    }

    fun sendCandidate(c: RTCCandidate) {
        peer?.candidateChannel?.trySend(c)
    }

    fun close() {
        sdpChannel.close()
        candidateChannel.close()
    }
}