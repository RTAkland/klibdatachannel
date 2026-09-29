/*
 * Copyright © 2026 RTAkland
 * Author: RTAkland
 * Date: 2026-09-30
 */


package test

import cn.rtast.webrtc.*
import kotlinx.coroutines.*
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class TestRTCNoACK {
    private val publicStun = "stun.cheapvoip.com"
    private val testTurnHost = "global.relay.metered.ca"
    private val testTurnUser = "053ab4513c7fbb2523e627d4"
    private val testTurnPass = "P2bVZlYXTzmzsu4V"

    @Test
    fun testStunOnly() = runBlocking {
        PeerConnectionFactory.init(RTCLogLevel.INFO)
        val scope = CoroutineScope(Dispatchers.IO)
        val config = rtcConfiguration {
            stun(publicStun)
            iceTransportPolicy = RTCIceTransportPolicy.ALL
        }

        runScenario(scope, "STUN-only", config)
    }

    @Test
    fun testStunAndTurn() = runBlocking {
        PeerConnectionFactory.init(RTCLogLevel.INFO)
        val scope = CoroutineScope(Dispatchers.IO)
        val config = rtcConfiguration {
            stun(publicStun)
            turn(testTurnHost, 80, testTurnUser, testTurnPass, RTCTransport.UDP)
            turn(testTurnHost, 80, testTurnUser, testTurnPass, RTCTransport.TCP)
            iceTransportPolicy = RTCIceTransportPolicy.ALL
        }

        runScenario(scope, "STUN+TURN(ALL)", config)
    }

    @Test
    fun testTurnRelayOnly() = runBlocking {
        PeerConnectionFactory.init(RTCLogLevel.WARNING)
        val scope = CoroutineScope(Dispatchers.Default)
        val config = rtcConfiguration {
            turn(testTurnHost, 80, testTurnUser, testTurnPass, RTCTransport.UDP)
            turn(testTurnHost, 443, testTurnUser, testTurnPass, RTCTransport.TCP)
            iceTransportPolicy = RTCIceTransportPolicy.RELAY
        }

        runScenario(scope, "TURN-RELAY", config)
    }

    private suspend fun runScenario(scope: CoroutineScope, label: String, config: RTCConfiguration) {
        val sigA = LoopbackSignaling()
        val sigB = LoopbackSignaling()
        sigA.peer = sigB
        sigB.peer = sigA

        val pcA = PeerConnectionFactory.createPeerConnection(scope, config)
        val pcB = PeerConnectionFactory.createPeerConnection(scope, config)

        pcA.onLocalDescription { sdp, type -> sigA.sendSdp(sdp, type) }
        pcA.onLocalCandidate { cand, mid -> sigA.sendCandidate(cand, mid) }
        pcA.onStateChange { println("[$label][A] state = $it") }

        sigA.onSdp { sdp, type -> pcA.setRemoteDescription(sdp, type) }
        sigA.onCandidate { candidate, mid -> pcA.addRemoteCandidate(candidate, mid) }

        pcB.onLocalDescription { sdp, type -> sigB.sendSdp(sdp, type) }
        pcB.onLocalCandidate { candidate, mid -> sigB.sendCandidate(candidate, mid) }
        pcB.onStateChange { println("[$label][B] state = $it") }

        sigB.onSdp { sdp, type ->
            pcB.setRemoteDescription(sdp, type)
            if (type == "offer") pcB.createAnswer()
        }
        sigB.onCandidate { candidate, mid -> pcB.addRemoteCandidate(candidate, mid) }

        val gotAtA = CompletableDeferred<Unit>()
        val gotAtB = CompletableDeferred<Unit>()
        pcB.onDataChannel { dc ->
            println(pcB.selectedConnectionMode())
            dc.onMessage { msg ->
                if (msg is RTCDataChannelMessage.Text) {
                    println("[$label][B] recv text: ${msg.value}")
                    if (!gotAtB.isCompleted) gotAtB.complete(Unit)
                }
            }
            dc.onOpen {
                scope.launch {
                    delay(500.milliseconds)
                    dc.sendText("hello from B")
                }
            }
        }

        val dcA = pcA.createDataChannel("chat", reliability = RTCDataChannelReliability.Reliable)
        dcA.onMessage { msg ->
            if (msg is RTCDataChannelMessage.Text) {
                println("[$label][A] recv text: ${msg.value}")
                if (!gotAtA.isCompleted) gotAtA.complete(Unit)
            }
        }
        dcA.onOpen {
            scope.launch {
                delay(500.milliseconds)
                dcA.sendText("hello from A")
            }
        }

        println("[$label] A creating offer")
        pcA.createOffer()

        val ok = withTimeoutOrNull(30.seconds) {
            awaitAll(gotAtA, gotAtB)
            true
        } ?: false

        if (ok) {
            println("\n[$label]PASS")
        } else {
            println("\n[$label]TIMEOUT")
        }

        delay(500.milliseconds)
        dcA.close()
        pcA.close()
        pcB.close()
        PeerConnectionFactory.cleanup()
    }
}