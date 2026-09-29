/*
 * Copyright © 2026 RTAkland
 * Author: RTAkland
 * Date: 2026-09-28
 */

@file:OptIn(ExperimentalForeignApi::class)

package cn.rtast.webrtc

import kotlinx.cinterop.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import libdatachannel.*
import platform.posix.memset
import kotlin.concurrent.Volatile

public class RTCPeerConnection internal constructor(scope: CoroutineScope, iceConfig: RTCConfiguration) {
    public constructor(scope: CoroutineScope) : this(scope, rtcConfiguration {})

    private var pc: Int = -1
    private var selfRef: StableRef<RTCPeerConnection>? = null
    private val events = Channel<RTCPeerEvent>(Channel.UNLIMITED)
    internal val channels = mutableMapOf<Int, RTCDataChannel>()

    private var onLocalDescription: ((String, String) -> Unit)? = null
    private var onLocalCandidate: ((String, String) -> Unit)? = null
    private var onStateChange: ((RTCConnectionState) -> Unit)? = null
    private var onDataChannel: ((RTCDataChannel) -> Unit)? = null

    @Volatile
    private var remoteDescriptionSet = false

    @Volatile
    private var offerSent = false

    @Volatile
    private var answerSent = false
    private val pendingCandidates = mutableListOf<Pair<String, String>>()
    private var lastLocalDescription: Pair<String, String>? = null
    private val seenCandidates = mutableSetOf<Pair<String, String>>()

    public val localDescription: String
        get() {
            if (pc < 0) return ""
            return memScoped {
                val bufSize = 8192
                val buf = allocArray<ByteVar>(bufSize)
                val r = rtcGetLocalDescription(pc, buf, bufSize)
                if (r < 0) "" else buf.toKString()
            }
        }

    public val localDescriptionType: String?
        get() {
            if (pc < 0) return null
            return memScoped {
                val bufSize = 64
                val buf = allocArray<ByteVar>(bufSize)
                val r = rtcGetLocalDescriptionType(pc, buf, bufSize)
                if (r < 0) null else buf.toKString()
            }
        }

    public val remoteDescription: String
        get() {
            if (pc < 0) return ""
            return memScoped {
                val bufSize = 8192
                val buf = allocArray<ByteVar>(bufSize)
                val r = rtcGetRemoteDescription(pc, buf, bufSize)
                if (r < 0) "" else buf.toKString()
            }
        }

    public val remoteDescriptionType: String?
        get() {
            if (pc < 0) return null
            return memScoped {
                val bufSize = 64
                val buf = allocArray<ByteVar>(bufSize)
                val r = rtcGetRemoteDescriptionType(pc, buf, bufSize)
                if (r < 0) null else buf.toKString()
            }
        }

    public val localSessionDescription: RTCSessionDescription?
        get() {
            val type = localDescriptionType ?: return null
            val sdp = localDescription
            if (sdp.isEmpty()) return null
            return RTCSessionDescription(type, sdp)
        }

    public val remoteSessionDescription: RTCSessionDescription?
        get() {
            val type = remoteDescriptionType ?: return null
            val sdp = remoteDescription
            if (sdp.isEmpty()) return null
            return RTCSessionDescription(type, sdp)
        }

    init {
        pc = createPeerConnection(iceConfig)
        check(pc >= 0) { "Failed to create PeerConnection: $pc" }
        selfRef = StableRef.create(this)
        val user = selfRef!!.asCPointer()
        rtcSetUserPointer(pc, user)
        rtcSetLocalDescriptionCallback(pc, localDescriptionCallback)
        rtcSetLocalCandidateCallback(pc, localCandidateCallback)
        rtcSetStateChangeCallback(pc, stateCallback)
        rtcSetDataChannelCallback(pc, dataChannelCallback)
        scope.launch { for (e in events) dispatch(e) }
    }

    public val isClosed: Boolean get() = pc < 0

    public fun onLocalDescription(block: (String, String) -> Unit) {
        this.onLocalDescription = block
    }

    public fun onLocalCandidate(block: (String, String) -> Unit) {
        this.onLocalCandidate = block
    }

    public fun onStateChange(block: (RTCConnectionState) -> Unit) {
        this.onStateChange = block
    }

    public fun onDataChannel(block: (RTCDataChannel) -> Unit) {
        this.onDataChannel = block
    }

    public fun selectedCandidatePair(): RTCCandidatePair? {
        if (pc < 0) return null
        return memScoped {
            val bufSize = 512
            val local = allocArray<ByteVar>(bufSize)
            val remote = allocArray<ByteVar>(bufSize)
            val r = rtcGetSelectedCandidatePair(pc, local, bufSize, remote, bufSize)
            if (r <= 0) null
            else RTCCandidatePair(local.toKString(), remote.toKString())
        }
    }

    public fun selectedConnectionMode(): RTCConnectionMode {
        val pair = selectedCandidatePair() ?: return RTCConnectionMode.UNKNOWN
        val localIsRelay = pair.local.contains("typ relay")
        val remoteIsRelay = pair.remote.contains("typ relay")
        return when {
            localIsRelay || remoteIsRelay -> RTCConnectionMode.RELAY
            pair.local.contains("typ srflx") || pair.remote.contains("typ srflx") -> RTCConnectionMode.P2P_STUN
            pair.local.contains("typ host") || pair.remote.contains("typ host") -> RTCConnectionMode.DIRECT
            else -> RTCConnectionMode.UNKNOWN
        }
    }

    private fun createPeerConnection(cfg: RTCConfiguration): Int = memScoped {
        val c = alloc<rtcConfiguration>()
        memset(c.ptr, 0, sizeOf<rtcConfiguration>().convert())
        c.iceTransportPolicy = cfg.iceTransportPolicy.value
        c.enableIceTcp = cfg.enableIceTcp
        c.enableIceUdpMux = cfg.enableIceUdpMux
        c.disableAutoNegotiation = cfg.disableAutoNegotiation
        c.bindAddress = cfg.bindAddress?.cstr?.ptr
        c.proxyServer = cfg.proxyServer?.cstr?.ptr
        c.portRangeBegin = cfg.portRangeBegin.toUShort()
        c.portRangeEnd = cfg.portRangeEnd.toUShort()
        c.mtu = cfg.mtu
        c.maxMessageSize = cfg.maxMessageSize
        c.certificateType = cfg.certificateType.value
        if (cfg.iceServers.isNotEmpty()) {
            val urlPtrs = cfg.iceServers.map { it.cstr.getPointer(this) }
            val urlArray = allocArray<CPointerVar<ByteVar>>(urlPtrs.size)
            urlPtrs.forEachIndexed { i, p -> urlArray[i] = p }
            c.iceServers = urlArray
            c.iceServersCount = urlPtrs.size
        }
        rtcCreatePeerConnection(c.ptr)
    }

    public fun createOffer() {
        if (pc < 0) return
        if (offerSent) return
        offerSent = true
        seenCandidates.clear()
        rtcSetLocalDescription(pc, "offer")
    }

    public fun createAnswer() {
        if (pc < 0) return
        if (answerSent) return
        answerSent = true
        rtcSetLocalDescription(pc, "answer")
    }

    public fun setRemoteDescription(sdp: String, type: String) {
        if (pc < 0) return
        val r = memScoped { rtcSetRemoteDescription(pc, sdp.cstr.ptr, type.cstr.ptr) }
        if (r < 0) {
            platform.posix.fprintf(platform.posix.stderr, "[pc] setRemoteDescription failed: %d\n", r)
            return
        }
        remoteDescriptionSet = true
        flushPendingCandidates()
    }

    public fun addRemoteCandidate(candidate: String, mid: String) {
        if (pc < 0) return
        if (!remoteDescriptionSet) {
            pendingCandidates += candidate to mid
            return
        }
        memScoped { rtcAddRemoteCandidate(pc, candidate.cstr.ptr, mid.cstr.ptr) }
    }

    private fun flushPendingCandidates() {
        if (pendingCandidates.isEmpty()) return
        val snapshot = pendingCandidates.toList()
        pendingCandidates.clear()
        memScoped {
            snapshot.forEach { (candidate, mid) ->
                rtcAddRemoteCandidate(pc, candidate.cstr.ptr, mid.cstr.ptr)
            }
        }
    }

    public fun createDataChannel(
        label: String,
        protocol: String = "",
        reliability: RTCDataChannelReliability = RTCDataChannelReliability.Reliable,
    ): RTCDataChannel {
        check(pc >= 0) { "PeerConnection closed" }
        val dcId = memScoped {
            val init = alloc<rtcDataChannelInit>()
            memset(init.ptr, 0, sizeOf<rtcDataChannelInit>().convert())
            when (reliability) {
                RTCDataChannelReliability.Reliable -> {}
                RTCDataChannelReliability.ReliableUnordered -> {
                    init.reliability.unordered = true
                }

                is RTCDataChannelReliability.MaxRetransmits -> {
                    init.reliability.unreliable = true
                    init.reliability.maxRetransmits = reliability.count.toUInt()
                }

                is RTCDataChannelReliability.MaxPacketLifeTime -> {
                    init.reliability.unreliable = true
                    init.reliability.maxPacketLifeTime = reliability.millis.toUInt()
                }

                is RTCDataChannelReliability.MaxRetransmitsUnordered -> {
                    init.reliability.unordered = true
                    init.reliability.unreliable = true
                    init.reliability.maxRetransmits = reliability.count.toUInt()
                }

                is RTCDataChannelReliability.MaxPacketLifeTimeUnordered -> {
                    init.reliability.unordered = true
                    init.reliability.unreliable = true
                    init.reliability.maxPacketLifeTime = reliability.millis.toUInt()
                }
            }

            if (protocol.isNotEmpty()) {
                init.protocol = protocol.cstr.ptr
            }
            rtcCreateDataChannelEx(pc, label.cstr.ptr, init.ptr)
        }
        check(dcId >= 0) { "Failed to create DataChannel" }
        val ch = RTCDataChannel(dcId, label, this)
        channels[dcId] = ch
        return ch
    }

    public fun createDataChannel(label: String): RTCDataChannel =
        createDataChannel(label, "", RTCDataChannelReliability.Reliable)

    public fun close() {
        if (pc < 0) return
        channels.values.toList().forEach { it.close() }
        pendingCandidates.clear()
        seenCandidates.clear()
        remoteDescriptionSet = false
        offerSent = false
        answerSent = false
        lastLocalDescription = null
        rtcClosePeerConnection(pc)
        rtcDeletePeerConnection(pc)
        pc = -1
        selfRef?.dispose()
        selfRef = null
        events.close()
    }

    internal fun removeChannel(id: Int) {
        channels.remove(id)
    }

    private fun dispatch(event: RTCPeerEvent) {
        when (event) {
            is RTCPeerEvent.LocalDescription -> {
                val key = event.type to event.sdp
                if (lastLocalDescription == key) return
                lastLocalDescription = key
                onLocalDescription?.invoke(event.sdp, event.type)
            }

            is RTCPeerEvent.LocalCandidate -> {
                val key = event.candidate to event.mid
                if (!seenCandidates.add(key)) return
                onLocalCandidate?.invoke(event.candidate, event.mid)
            }

            is RTCPeerEvent.State -> onStateChange?.invoke(event.state)
            is RTCPeerEvent.IncomingChannel -> onDataChannel?.invoke(event.channel)
        }
    }

    public companion object {
        private val localDescriptionCallback =
            staticCFunction<Int, CPointer<ByteVar>?, CPointer<ByteVar>?, COpaquePointer?, Unit> { _, sdp, type, user ->
                val self = user?.asStableRef<RTCPeerConnection>()?.get() ?: return@staticCFunction
                self.events.trySend(
                    RTCPeerEvent.LocalDescription(
                        sdp?.toKString() ?: "",
                        type?.toKString() ?: "",
                    )
                )
            }

        private val localCandidateCallback =
            staticCFunction<Int, CPointer<ByteVar>?, CPointer<ByteVar>?, COpaquePointer?, Unit> { _, candidate, mid, user ->
                val self = user?.asStableRef<RTCPeerConnection>()?.get() ?: return@staticCFunction
                self.events.trySend(RTCPeerEvent.LocalCandidate(candidate?.toKString() ?: "", mid?.toKString() ?: ""))
            }

        private val stateCallback =
            staticCFunction<Int, rtcState, COpaquePointer?, Unit> { _, state, user ->
                val self = user?.asStableRef<RTCPeerConnection>()?.get() ?: return@staticCFunction
                self.events.trySend(RTCPeerEvent.State(RTCConnectionState.from(state.toInt())))
            }

        private val dataChannelCallback =
            staticCFunction<Int, Int, COpaquePointer?, Unit> { _, dcId, user ->
                val self = user?.asStableRef<RTCPeerConnection>()?.get() ?: return@staticCFunction
                val label: String = memScoped {
                    val buf = allocArray<ByteVar>(256)
                    val r = rtcGetDataChannelLabel(dcId, buf, 256)
                    if (r < 0) "unknown" else buf.toKString()
                }
                val ch = RTCDataChannel(dcId, label, self)
                self.channels[dcId] = ch
                self.events.trySend(RTCPeerEvent.IncomingChannel(ch))
            }
    }
}