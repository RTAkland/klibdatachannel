/*
 * Copyright © 2026 RTAkland
 * Author: RTAkland
 * Date: 2026-10-02
 */

@file:OptIn(ExperimentalForeignApi::class)

package cn.rtast.webrtc

import cn.rtast.webrtc.configuration.*
import cn.rtast.webrtc.state.*
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.cinterop.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import libdatachannel.*
import platform.posix.memset
import kotlin.concurrent.Volatile

public actual class RTCPeerConnection internal actual constructor(
    parentScope: CoroutineScope, iceConfig: RTCConfiguration,
) {
    public actual constructor(scope: CoroutineScope) : this(scope, rtcConfiguration {})

    private var pc: Int = -1
    private var selfRef: StableRef<RTCPeerConnection>? = null

    private val nativeEvents = Channel<NativePeerConnectionEvent>(Channel.UNLIMITED)

    internal val pcScope = CoroutineScope(
        parentScope.coroutineContext +
                SupervisorJob(parentScope.coroutineContext[Job])
    )

    private val channelsLock = SynchronizedObject()
    private val channels = mutableMapOf<Int, RTCDataChannel>()

    private val _connectionState = MutableStateFlow(RTCConnectionState.NEW)
    public actual val connectionState: StateFlow<RTCConnectionState> = _connectionState.asStateFlow()

    private val _iceState = MutableStateFlow(RTCIceState.NEW)
    public actual val iceState: StateFlow<RTCIceState> = _iceState.asStateFlow()

    private val _gatheringState = MutableStateFlow(RTCGatheringState.NEW)
    public actual val gatheringState: StateFlow<RTCGatheringState> = _gatheringState.asStateFlow()

    private val _selectedCandidatePair = MutableStateFlow<RTCSelectedCandidate?>(null)
    public actual val selectedCandidatePair: StateFlow<RTCSelectedCandidate?> = _selectedCandidatePair.asStateFlow()

    private val _localDescriptions = MutableSharedFlow<RTCSessionDescription>(1, 4, BufferOverflow.DROP_OLDEST)
    public actual val localDescriptions: SharedFlow<RTCSessionDescription> = _localDescriptions.asSharedFlow()

    private val _localCandidates = MutableSharedFlow<RTCCandidate>(0, 64, BufferOverflow.DROP_OLDEST)
    public actual val localCandidates: SharedFlow<RTCCandidate> = _localCandidates.asSharedFlow()

    private val _incomingDataChannels = MutableSharedFlow<RTCDataChannel>(0, 8, BufferOverflow.SUSPEND)
    public actual val incomingDataChannels: SharedFlow<RTCDataChannel> = _incomingDataChannels.asSharedFlow()

    @Volatile
    private var remoteDescriptionSet = false

    @Volatile
    private var offerSent = false

    @Volatile
    private var answerSent = false
    private val pendingCandidates = mutableListOf<RTCCandidate>()
    private var lastLocalDescription: RTCSessionDescription? = null
    private val seenCandidates = mutableSetOf<Pair<String, String>>()

    public actual val localSessionDescription: RTCSessionDescription?
        get() {
            if (pc < 0) return null
            val type = memScoped {
                val bufSize = 64
                val buf = allocArray<ByteVar>(bufSize)
                val r = rtcGetLocalDescriptionType(pc, buf, bufSize)
                if (r < 0) null else buf.toKString()
            } ?: return null
            val sdp = memScoped {
                val bufSize = 8192
                val buf = allocArray<ByteVar>(bufSize)
                val r = rtcGetLocalDescription(pc, buf, bufSize)
                if (r < 0) "" else buf.toKString()
            }
            if (sdp.isEmpty()) return null
            return RTCSessionDescription(type, sdp)
        }

    public actual val remoteSessionDescription: RTCSessionDescription?
        get() {
            if (pc < 0) return null
            val type = memScoped {
                val bufSize = 64
                val buf = allocArray<ByteVar>(bufSize)
                if (rtcGetRemoteDescriptionType(pc, buf, bufSize) < 0) null else buf.toKString()
            } ?: return null
            val sdp = memScoped {
                val bufSize = 8192
                val buf = allocArray<ByteVar>(bufSize)
                if (rtcGetRemoteDescription(pc, buf, bufSize) < 0) "" else buf.toKString()
            }
            if (sdp.isEmpty()) return null
            return RTCSessionDescription(type, sdp)
        }

    init {
        pc = createPeerConnection(iceConfig)
        check(pc >= 0) { "Failed to create PeerConnection: $pc" }
        selfRef = StableRef.create(this)
        rtcSetUserPointer(pc, selfRef!!.asCPointer())
        rtcSetLocalDescriptionCallback(pc, localDescriptionCallback)
        rtcSetLocalCandidateCallback(pc, localCandidateCallback)
        rtcSetStateChangeCallback(pc, stateCallback)
        rtcSetIceStateChangeCallback(pc, iceStateCallback)
        rtcSetGatheringStateChangeCallback(pc, gatheringStateCallback)
        rtcSetDataChannelCallback(pc, dataChannelCallback)
        pcScope.launch { consumeNativeEvents() }
    }

    public actual val isClosed: Boolean get() = pc < 0

    private fun querySelectedCandidatePair(): RTCSelectedCandidate? {
        if (pc < 0) return null
        return memScoped {
            val bufSize = 512
            val local = allocArray<ByteVar>(bufSize)
            val remote = allocArray<ByteVar>(bufSize)
            val r = rtcGetSelectedCandidatePair(pc, local, bufSize, remote, bufSize)
            if (r <= 0) null else RTCSelectedCandidate(local.toKString(), remote.toKString())
        }
    }

    /**
     * Derives the transport type (host/srflx/relay) from the selected pair
     */
    public actual fun selectedIceTransportType(): RTCIceTransportType {
        val pair = _selectedCandidatePair.value ?: return RTCIceTransportType.UNKNOWN
        val localIsRelay = pair.local.contains("typ relay")
        val remoteIsRelay = pair.remote.contains("typ relay")
        return when {
            localIsRelay || remoteIsRelay -> RTCIceTransportType.RELAY
            pair.local.contains("typ srflx") || pair.remote.contains("typ srflx") -> RTCIceTransportType.SRFLX
            pair.local.contains("typ host") || pair.remote.contains("typ host") -> RTCIceTransportType.HOST
            else -> RTCIceTransportType.UNKNOWN
        }
    }

    private fun createPeerConnection(cfg: RTCConfiguration): Int = memScoped {
        val c = alloc<rtcConfiguration>()
        memset(c.ptr, 0, sizeOf<rtcConfiguration>().convert())
        c.iceTransportPolicy = cfg.iceTransportPolicy.toNative()
        c.enableIceTcp = cfg.enableIceTcp
        c.enableIceUdpMux = cfg.enableIceUdpMux
        c.disableAutoNegotiation = cfg.disableAutoNegotiation
        c.bindAddress = cfg.bindAddress?.cstr?.ptr
        c.proxyServer = cfg.proxyServer?.cstr?.ptr
        c.portRangeBegin = cfg.portRangeBegin.toUShort()
        c.portRangeEnd = cfg.portRangeEnd.toUShort()
        c.mtu = cfg.mtu
        c.maxMessageSize = cfg.maxMessageSize
        c.certificateType = cfg.certificateType.toNative()
        if (cfg.iceServers.isNotEmpty()) {
            val urlPtrs = cfg.iceServers.map { it.cstr.getPointer(this) }
            val urlArray = allocArray<CPointerVar<ByteVar>>(urlPtrs.size)
            urlPtrs.forEachIndexed { i, p -> urlArray[i] = p }
            c.iceServers = urlArray
            c.iceServersCount = urlPtrs.size
        }
        rtcCreatePeerConnection(c.ptr)
    }

    public actual fun createOffer() {
        if (pc < 0 || offerSent) return
        offerSent = true
        seenCandidates.clear()
        rtcSetLocalDescription(pc, "offer")
    }

    public actual fun createAnswer() {
        if (pc < 0 || answerSent) return
        answerSent = true
        rtcSetLocalDescription(pc, "answer")
    }

    public actual fun setRemoteDescription(sdp: String, type: String) {
        if (pc < 0) return
        val r = memScoped { rtcSetRemoteDescription(pc, sdp.cstr.ptr, type.cstr.ptr) }
        if (r < 0) {
            platform.posix.fprintf(platform.posix.stderr, "[pc] setRemoteDescription failed: %d\n", r)
            return
        }
        remoteDescriptionSet = true
        flushPendingCandidates()
    }

    public actual fun addRemoteCandidate(candidate: String, mid: String) {
        addRemoteCandidate(RTCCandidate(candidate, mid))
    }

    public actual fun addRemoteCandidate(candidate: RTCCandidate) {
        if (pc < 0) return
        if (!remoteDescriptionSet) {
            pendingCandidates += candidate
            return
        }
        memScoped {
            rtcAddRemoteCandidate(pc, candidate.candidate.cstr.ptr, candidate.mid.cstr.ptr)
        }
    }

    private fun flushPendingCandidates() {
        if (pendingCandidates.isEmpty()) return
        val snapshot = pendingCandidates.toList()
        pendingCandidates.clear()
        memScoped {
            snapshot.forEach { c -> rtcAddRemoteCandidate(pc, c.candidate.cstr.ptr, c.mid.cstr.ptr) }
        }
    }

    /**
     * Creates an initiated datachannel with configuration
     */
    public actual fun createDataChannel(
        label: String,
        protocol: String,
        config: RTCDataChannelConfig,
    ): RTCDataChannel {
        check(pc >= 0) { "PeerConnection closed" }
        val effectiveProtocol = config.protocol.ifEmpty { protocol }
        val dcId = memScoped {
            val init = alloc<rtcDataChannelInit>()
            memset(init.ptr, 0, sizeOf<rtcDataChannelInit>().convert())
            init.reliability.unordered = !config.ordered
            if (config.maxRetransmits != null) {
                init.reliability.unreliable = true
                init.reliability.maxRetransmits = config.maxRetransmits.toUInt()
            } else if (config.maxPacketLifeTime != null) {
                init.reliability.unreliable = true
                init.reliability.maxPacketLifeTime = config.maxPacketLifeTime.toUInt()
            }

            if (effectiveProtocol.isNotEmpty()) init.protocol = effectiveProtocol.cstr.ptr
            init.negotiated = config.negotiated
            rtcCreateDataChannelEx(pc, label.cstr.ptr, init.ptr)
        }
        check(dcId >= 0) { "Failed to create DataChannel" }
        val ch = RTCDataChannel(dcId, label, this, pcScope)
        putChannel(dcId, ch)
        return ch
    }

    /**
     * Creates a reliable, ordered datachannel
     */
    public actual fun createDataChannel(label: String): RTCDataChannel =
        createDataChannel(label, "", RTCDataChannelConfig.Reliable)

    /**
     * Creates a datachannel with an explicit config
     */
    public actual fun createDataChannel(label: String, config: RTCDataChannelConfig): RTCDataChannel =
        createDataChannel(label, "", config)

    internal fun putChannel(id: Int, ch: RTCDataChannel) {
        synchronized(channelsLock) { channels[id] = ch }
    }

    internal fun removeChannel(id: Int) {
        synchronized(channelsLock) { channels.remove(id) }
    }

    private fun snapshotChannels(): List<RTCDataChannel> =
        synchronized(channelsLock) { channels.values.toList() }

    public actual fun close() {
        if (pc < 0) return
        snapshotChannels().forEach { it.close() }
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
        nativeEvents.close()
        _connectionState.value = RTCConnectionState.CLOSED
        pcScope.cancel()
    }

    private suspend fun consumeNativeEvents() {
        for (e in nativeEvents) {
            when (e) {
                is NativePeerConnectionEvent.LocalDescription -> {
                    val sd = RTCSessionDescription(e.type, e.sdp)
                    if (lastLocalDescription == sd) continue
                    lastLocalDescription = sd
                    _localDescriptions.emit(sd)
                }

                is NativePeerConnectionEvent.LocalCandidate -> {
                    val key = e.candidate to e.mid
                    if (!seenCandidates.add(key)) continue
                    _localCandidates.emit(RTCCandidate(e.candidate, e.mid))
                    querySelectedCandidatePair()?.let { _selectedCandidatePair.value = it }
                }

                is NativePeerConnectionEvent.ConnectionState -> {
                    _connectionState.value = e.state
                    if (e.state == RTCConnectionState.CONNECTED ||
                        e.state == RTCConnectionState.DISCONNECTED
                    ) querySelectedCandidatePair()?.let { _selectedCandidatePair.value = it }
                }

                is NativePeerConnectionEvent.IceState -> _iceState.value = e.state
                is NativePeerConnectionEvent.GatheringState -> _gatheringState.value = e.state
                is NativePeerConnectionEvent.IncomingChannel -> _incomingDataChannels.emit(e.channel)
            }
        }
    }

    private sealed interface NativePeerConnectionEvent {
        class LocalDescription(val sdp: String, val type: String) : NativePeerConnectionEvent
        class LocalCandidate(val candidate: String, val mid: String) : NativePeerConnectionEvent
        class ConnectionState(val state: RTCConnectionState) : NativePeerConnectionEvent
        class IceState(val state: RTCIceState) : NativePeerConnectionEvent
        class GatheringState(val state: RTCGatheringState) : NativePeerConnectionEvent
        class IncomingChannel(val channel: RTCDataChannel) : NativePeerConnectionEvent
    }

    public companion object {
        private val localDescriptionCallback =
            staticCFunction<Int, CPointer<ByteVar>?, CPointer<ByteVar>?, COpaquePointer?, Unit> { _, sdp, type, user ->
                val self = user?.asStableRef<RTCPeerConnection>()?.get() ?: return@staticCFunction
                self.nativeEvents.trySend(
                    NativePeerConnectionEvent.LocalDescription(
                        sdp = sdp?.toKString() ?: "",
                        type = type?.toKString() ?: "",
                    )
                )
            }

        private val localCandidateCallback =
            staticCFunction<Int, CPointer<ByteVar>?, CPointer<ByteVar>?, COpaquePointer?, Unit> { _, candidate, mid, user ->
                val self = user?.asStableRef<RTCPeerConnection>()?.get() ?: return@staticCFunction
                self.nativeEvents.trySend(
                    NativePeerConnectionEvent.LocalCandidate(
                        candidate = candidate?.toKString() ?: "",
                        mid = mid?.toKString() ?: "",
                    )
                )
            }

        private val stateCallback =
            staticCFunction<Int, rtcState, COpaquePointer?, Unit> { _, state, user ->
                val self = user?.asStableRef<RTCPeerConnection>()?.get() ?: return@staticCFunction
                self.nativeEvents.trySend(
                    NativePeerConnectionEvent.ConnectionState(state.toRTCConnectionState())
                )
            }

        private val iceStateCallback =
            staticCFunction<Int, rtcIceState, COpaquePointer?, Unit> { _, state, user ->
                val self = user?.asStableRef<RTCPeerConnection>()?.get() ?: return@staticCFunction
                self.nativeEvents.trySend(
                    NativePeerConnectionEvent.IceState(state.toRTCIceState())
                )
            }

        private val gatheringStateCallback =
            staticCFunction<Int, rtcGatheringState, COpaquePointer?, Unit> { _, state, user ->
                val self = user?.asStableRef<RTCPeerConnection>()?.get() ?: return@staticCFunction
                self.nativeEvents.trySend(
                    NativePeerConnectionEvent.GatheringState(state.toRTCGatheringState())
                )
            }

        private val dataChannelCallback =
            staticCFunction<Int, Int, COpaquePointer?, Unit> { _, dcId, user ->
                val self = user?.asStableRef<RTCPeerConnection>()?.get() ?: return@staticCFunction
                val label: String = memScoped {
                    val buf = allocArray<ByteVar>(256)
                    val r = rtcGetDataChannelLabel(dcId, buf, 256)
                    if (r < 0) "unknown" else buf.toKString()
                }
                val ch = RTCDataChannel(dcId, label, self, self.pcScope)
                self.putChannel(dcId, ch)
                self.nativeEvents.trySend(NativePeerConnectionEvent.IncomingChannel(ch))
            }
    }
}