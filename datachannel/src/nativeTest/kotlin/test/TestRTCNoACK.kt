/*
 * Copyright © 2026 RTAkland
 * Author: RTAkland
 * Date: 2026-09-30
 */

package test

import cn.rtast.webrtc.RTCPeerConnectionFactory
import cn.rtast.webrtc.configuration.*
import cn.rtast.webrtc.state.RTCDataChannelState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class TestRTCNoACK {
    private val publicStun = "stun.cheapvoip.com"
    private val testTurnHost = "global.relay.metered.ca"
    private val testTurnUser = "053ab4513c7fbb2523e627d4"
    private val testTurnPass = "P2bVZlYXTzmzsu4V"

    private val factory = RTCPeerConnectionFactory(RTCLogLevel.ERROR)

    @Test
    fun testStunOnly() = runBlocking {
        factory.onError {
            println(it)
        }
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val config = rtcConfiguration {
            stun(publicStun)
            iceTransportPolicy = RTCIceTransportPolicy.ALL
            disableAutoNegotiation = true
        }
        try {
            runScenario(scope, "STUN-only", config)
        } finally {
            scope.cancel()
            factory.cleanup()
        }
    }

    @Test
    fun testStunAndTurn() = runBlocking {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val config = rtcConfiguration {
            stun(publicStun)
            turn(testTurnHost, 80, testTurnUser, testTurnPass, RTCTransport.UDP)
            turn(testTurnHost, 80, testTurnUser, testTurnPass, RTCTransport.TCP)
            iceTransportPolicy = RTCIceTransportPolicy.ALL
            disableAutoNegotiation = true
        }
        try {
            runScenario(scope, "STUN+TURN(ALL)", config)
        } finally {
            scope.cancel()
            factory.cleanup()
        }
    }

    @Test
    fun testTurnRelayOnly() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val config = rtcConfiguration {
            turn(testTurnHost, 80, testTurnUser, testTurnPass, RTCTransport.UDP)
            turn(testTurnHost, 443, testTurnUser, testTurnPass, RTCTransport.TCP)
            iceTransportPolicy = RTCIceTransportPolicy.RELAY
            disableAutoNegotiation = true
        }
        try {
            runScenario(scope, "TURN-RELAY", config)
        } finally {
            scope.cancel()
            factory.cleanup()
        }
    }

    private suspend fun runScenario(scope: CoroutineScope, label: String, config: RTCConfiguration) {
        val sigA = LoopbackSignaling()
        val sigB = LoopbackSignaling()
        sigA.peer = sigB
        sigB.peer = sigA

        val pcA = factory.createPeerConnection(scope, config)
        val pcB = factory.createPeerConnection(scope, config)

        val gotAtA = CompletableDeferred<Unit>()
        val gotAtB = CompletableDeferred<Unit>()
        pcA.localDescriptions.onEach { sigA.sendSdp(it) }.launchIn(scope)
        pcA.localCandidates.onEach { sigA.sendCandidate(it) }.launchIn(scope)
        pcA.connectionState.onEach { println("[$label][A] state = $it") }.launchIn(scope)
        sigA.remoteDescriptions.onEach { pcA.setRemoteDescription(it.sdp, it.type) }.launchIn(scope)
        sigA.remoteCandidates.onEach { pcA.addRemoteCandidate(it) }.launchIn(scope)
        pcB.localDescriptions.onEach { sigB.sendSdp(it) }.launchIn(scope)
        pcB.localCandidates.onEach { sigB.sendCandidate(it) }.launchIn(scope)
        pcB.connectionState.onEach { println("[$label][B] state = $it") }.launchIn(scope)
        sigB.remoteDescriptions.onEach { sd ->
            pcB.setRemoteDescription(sd.sdp, sd.type)
            pcB.createAnswer()
        }.launchIn(scope)

        sigB.remoteCandidates.onEach { pcB.addRemoteCandidate(it) }.launchIn(scope)
        pcB.incomingDataChannels.onEach { dc ->
            println(
                "[$label][B] incoming channel: ${dc.label}, " +
                        "mode = ${pcB.selectedIceTransportType()}"
            )
            dc.messages.text.onEach { msg ->
                println("[$label][B] recv text: $msg")
                gotAtB.complete(Unit)
            }.launchIn(scope)

            dc.state.filter { it == RTCDataChannelState.Open }
                .onEach {
                    delay(500.milliseconds)
                    dc.send("hello from B")
                }.launchIn(scope)
        }.launchIn(scope)

        val dcA = pcA.createDataChannel("chat", rtcDataChannelConfig {
            ordered = true
        })

        dcA.messages.text.onEach { msg ->
            println("[$label][A] recv text: $msg")
            gotAtA.complete(Unit)
        }.launchIn(scope)

        dcA.state.filter { it == RTCDataChannelState.Open }
            .onEach {
                delay(500.milliseconds)
                dcA.send("hello from A")
            }.launchIn(scope)

        println("[$label] A creating offer")
        pcA.createOffer()

        val ok = withTimeoutOrNull(30.seconds) {
            awaitAll(gotAtA, gotAtB)
            true
        } ?: false

        println(if (ok) "\n[$label]PASS" else "\n[$label]TIMEOUT")

        delay(500.milliseconds)
        dcA.close()
        pcA.close()
        pcB.close()
        sigA.close()
        sigB.close()
    }
}