# Get Started

This guide is about how to use webrtc-kotlin.

## About kotlin-webrtc

> `kotlin-webrtc` is a [libdatachannel](https://github.com/paullouisageneau/libdatachannel) wrapper based on kotlin
> cinterop, with no media (audio/video) support.

# Supported platforms

|         | x64 | arm64 |
|---------|:---:|:-----:|
| Linux   | ✔  |  ✔   |
| macOS   |  -  |   -   |
| Windows |  -  |   -   |

# Dependencies

```kotlin
repositories {
    maven("https://repo.rtast.cn/packages/")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("cn.rtast.webrtc:datachannel:0.1.5")
            // Get latest version https://repo.rtast.cn/packages/-/cn.rtast.webrtc:datachannel
        }
    }
}
```

# Basic Usage

```kotlin
val factory = RTCPeerConnectionFactory(RTCLogLevel.ERROR)
val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

val config = rtcConfiguration {
    stun("stun.l.google.com")
    turn("global.relay.metered.ca", 80, "username", "password", RTCTransport.UDP)

    iceTransportPolicy = RTCIceTransportPolicy.ALL
    disableAutoNegotiation = true
}
```

## Create PeerConnection

> This part is about how to create a peer connection between initiator and responder

```kotlin
// ------------------- Initiator ------------------- //

val initiator = factory.createPeerConnection(scope, config)
initiator.localDescriptions.onEach { sdp: RTCSessionDescription ->
    // Sending sdp to peer via your signaling server
    signalingInitiator.sendSdp(sdp)
}.launchIn(scope)

initiator.localCandidates.onEach { candidate: RTCCandidate ->
    // Sending candidate to peer via your signaling server
    signalingInitiator.sendCandidate(candidate)
}.launchIn(scope)

signalingInitiator.remoteDescriptions.onEach {
    // Receive and set responder sdp from signalign server
    initiator.setRemoteDescription(it.sdp, it.type)
}.launchIn(scope)

signalingInitiator.remoteCandidates.onEach {
    // Receive and set responder candidate from signalign server
    initiator.addRemoteCandidate(it)
}.launchIn(scope)

// Subscribe connection state
// ConnectionState is a coroutine flow
initiator.connectionState.onEach {
    println("[Initiator] state = $it")
}.launchIn(scope)

// ------------------- Responder ------------------- //

val responder = factory.createPeerConnection(scope, config)
responder.localDescriptions.onEach { sdp: RTCSessionDescription ->
    // Sending sdp to peer via your signaling server
    signalingResponder.sendSdp(sdp)
}.launchIn(scope)

responder.localCandidates.onEach { candidate: RTCCandidate ->
    // Sending candidate to peer via your signaling server
    signalingResponder.sendCandidate(candidate)
}.launchIn(scope)

signalingInitiator.remoteDescriptions.onEach {
    // Receive and set initiator sdp from signalign server
    responder.setRemoteDescription(it.sdp, it.type)
}.launchIn(scope)

signalingInitiator.remoteCandidates.onEach {
    // Receive and set initiator candidate from signalign server
    responder.addRemoteCandidate(it)
}.launchIn(scope)
```

## Receiving & sending data

> Since WebRTC is full-duplex, all operations performed by the initiator and the responder are symmetric.

```kotlin
responder.incomingDataChannels.onEach { dc ->
    println("[Responder]incoming channel: ${dc.label}, transport = ${pcB.selectedIceTransportType()}")

    // Receiving String from peer
    dc.messages.text.onEach { msg ->
        println("[Responder]received text: $msg")
    }.launchIn(scope)

    // Receiving ByteArray from peer
    dc.messages.bytes.onEach { msg ->
        println("[Responder]received bytes: ${msg.toString()}")
    }.launchIn(scope)

    // When channel is opened, sending a message to peer, it can be string or bytes
    dc.state.filter { it == RTCDataChannelState.Open }.onEach {
        delay(500.milliseconds)
        dc.send("Hello")  // Sending String to peer
        dc.send(byteArrayOf(0x01, 0x02, 0x03))  // Sending ByteArray to peer
    }.launchIn(scope)
}.launchIn(scope)

// Creating DataChannel on Initiator side
val initiatorDataChannel = initiator.createDataChannel(label = "message-channel", rtcDataChannelConfig {
    ordered = true
    maxRetransmits = 2
})
initiatorDataChannel.messages.text.onEach { msg ->
    println("[Initiator]received text from responder: $msg")
}

initiatorDataChannel.messages.bytes.onEach { msg ->
    println("[Initiator]received bytes from responder: ${msg.toString()}")
}

initiatorDataChannel.state.filter { it == RTCDataChannelState.Open }.onEach {
    delay(500.milliseconds)
    initiatorDataChannel.send("World")
    initiatorDataChannel.send(byteArrayOf(0x04, 0x05, 0x06))
}.launchIn(scope)
initiatorDataChannel.createOffer()

// ----------------------- Do clean up -------------------- //

initiatorDataChannel.close()
initiator.close()
responder.close()
factory.cleanup()
```