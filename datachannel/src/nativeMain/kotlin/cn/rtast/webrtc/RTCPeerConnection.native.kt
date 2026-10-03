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
import platform.posix.fprintf
import platform.posix.memset
import platform.posix.stderr
import kotlin.concurrent.Volatile

public actual class RTCPeerConnection internal actual constructor(
    parentScope: CoroutineScope, iceConfig: RTCConfiguration,
) {
    public actual constructor(scope: CoroutineScope) : this(scope, rtcConfiguration {})

    @Volatile
    private var pc: Int = -1

    @Volatile
    private var selfRef: StableRef<RTCPeerConnection>? = null
    private val lifecycleLock = SynchronizedObject()
    private val pcScope = CoroutineScope(
        parentScope.coroutineContext +
                SupervisorJob(parentScope.coroutineContext[Job])
    )

    private val nativeEvents = Channel<NativePeerConnectionEvent>(Channel.UNLIMITED)
    private val channelsLock = SynchronizedObject()
    private val channels = mutableMapOf<Int, RTCDataChannel>()
    private val _connectionState = MutableStateFlow(RTCConnectionState.NEW)
    private val _iceState = MutableStateFlow(RTCIceState.NEW)
    private val _gatheringState = MutableStateFlow(RTCGatheringState.NEW)
    private val _selectedCandidatePair = MutableStateFlow<RTCSelectedCandidate?>(null)
    private val _localDescriptions = MutableSharedFlow<RTCSessionDescription>(1, 4, BufferOverflow.DROP_OLDEST)
    private val _localCandidates = MutableSharedFlow<RTCCandidate>(0, 64, BufferOverflow.DROP_OLDEST)
    private val _incomingDataChannels = MutableSharedFlow<RTCDataChannel>(0, 8, BufferOverflow.SUSPEND)

    public actual val coroutineScope: CoroutineScope get() = pcScope
    public actual val connectionState: StateFlow<RTCConnectionState> = _connectionState.asStateFlow()
    public actual val iceState: StateFlow<RTCIceState> = _iceState.asStateFlow()
    public actual val gatheringState: StateFlow<RTCGatheringState> = _gatheringState.asStateFlow()
    public actual val selectedCandidatePair: StateFlow<RTCSelectedCandidate?> = _selectedCandidatePair.asStateFlow()
    public actual val localDescriptions: SharedFlow<RTCSessionDescription> = _localDescriptions.asSharedFlow()
    public actual val localCandidates: SharedFlow<RTCCandidate> = _localCandidates.asSharedFlow()
    public actual val incomingDataChannels: SharedFlow<RTCDataChannel> = _incomingDataChannels.asSharedFlow()

    private val negotiationLock = SynchronizedObject()

    @Volatile
    private var remoteDescriptionSet = false

    @Volatile
    private var offerSent = false

    @Volatile
    private var answerSent = false
    private val pendingCandidates = mutableListOf<RTCCandidate>()
    private val seenCandidates = mutableSetOf<Pair<String, String>>()

    @Volatile
    private var lastLocalDescription: RTCSessionDescription? = null

    public actual val localSessionDescription: RTCSessionDescription?
        get() {
            val handle = pc
            if (handle < 0) return null
            val type = memScoped {
                val bufSize = 64
                val buf = allocArray<ByteVar>(bufSize)
                if (rtcGetLocalDescriptionType(handle, buf, bufSize) < 0) null else buf.toKString()
            } ?: return null
            val sdp = memScoped {
                val bufSize = 8192
                val buf = allocArray<ByteVar>(bufSize)
                if (rtcGetLocalDescription(handle, buf, bufSize) < 0) "" else buf.toKString()
            }
            if (sdp.isEmpty()) return null
            return RTCSessionDescription(type, sdp)
        }

    public actual val remoteSessionDescription: RTCSessionDescription?
        get() {
            val handle = pc
            if (handle < 0) return null
            val type = memScoped {
                val bufSize = 64
                val buf = allocArray<ByteVar>(bufSize)
                if (rtcGetRemoteDescriptionType(handle, buf, bufSize) < 0) null else buf.toKString()
            } ?: return null
            val sdp = memScoped {
                val bufSize = 8192
                val buf = allocArray<ByteVar>(bufSize)
                if (rtcGetRemoteDescription(handle, buf, bufSize) < 0) "" else buf.toKString()
            }
            if (sdp.isEmpty()) return null
            return RTCSessionDescription(type, sdp)
        }

    init {
        val handle = createPeerConnection(iceConfig)
        check(handle >= 0) { "Failed to create PeerConnection: $handle" }
        pc = handle
        selfRef = StableRef.create(this)
        rtcSetUserPointer(handle, selfRef!!.asCPointer())
        rtcSetLocalDescriptionCallback(handle, localDescriptionCallback)
        rtcSetLocalCandidateCallback(handle, localCandidateCallback)
        rtcSetStateChangeCallback(handle, stateCallback)
        rtcSetIceStateChangeCallback(handle, iceStateCallback)
        rtcSetGatheringStateChangeCallback(handle, gatheringStateCallback)
        rtcSetDataChannelCallback(handle, dataChannelCallback)
        pcScope.launch { consumeNativeEvents() }
    }

    public actual val isClosed: Boolean get() = pc < 0

    private fun querySelectedCandidatePair(): RTCSelectedCandidate? {
        val handle = pc
        if (handle < 0) return null
        return memScoped {
            val bufSize = 512
            val local = allocArray<ByteVar>(bufSize)
            val remote = allocArray<ByteVar>(bufSize)
            val r = rtcGetSelectedCandidatePair(handle, local, bufSize, remote, bufSize)
            if (r <= 0) null else RTCSelectedCandidate(local.toKString(), remote.toKString())
        }
    }

    public actual fun selectedIceTransportType(): RTCIceTransportType {
        val pair = _selectedCandidatePair.value ?: return RTCIceTransportType.UNKNOWN
        val localType = candidateTypeOf(pair.local)
        val remoteType = candidateTypeOf(pair.remote)
        return when {
            localType == "relay" || remoteType == "relay" -> RTCIceTransportType.RELAY
            localType == "srflx" || remoteType == "srflx" -> RTCIceTransportType.SRFLX
            localType == "host" || remoteType == "host" -> RTCIceTransportType.HOST
            else -> RTCIceTransportType.UNKNOWN
        }
    }

    private fun candidateTypeOf(candidate: String): String? {
        val tokens = candidate.split(' ')
        val idx = tokens.indexOf("typ")
        return if (idx >= 0 && idx + 1 < tokens.size) tokens[idx + 1] else null
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
            val urlPtrs = cfg.iceServers.map { it.toString().cstr.getPointer(this) }
            val urlArray = allocArray<CPointerVar<ByteVar>>(urlPtrs.size)
            urlPtrs.forEachIndexed { i, p -> urlArray[i] = p }
            c.iceServers = urlArray
            c.iceServersCount = urlPtrs.size
        }
        rtcCreatePeerConnection(c.ptr)
    }

    public actual fun createOffer() {
        val handle = pc
        if (handle < 0 || offerSent) return
        offerSent = true
        synchronized(negotiationLock) { seenCandidates.clear() }
        rtcSetLocalDescription(handle, "offer")
    }

    public actual fun createAnswer() {
        val handle = pc
        if (handle < 0 || answerSent) return
        answerSent = true
        rtcSetLocalDescription(handle, "answer")
    }

    public actual fun setRemoteDescription(sdp: String, type: String) {
        val handle = pc
        if (handle < 0) return
        val r = memScoped { rtcSetRemoteDescription(handle, sdp.cstr.ptr, type.cstr.ptr) }
        if (r < 0) {
            fprintf(stderr, "[pc] setRemoteDescription failed: %d\n", r)
            return
        }
        remoteDescriptionSet = true
        flushPendingCandidates()
    }

    public actual fun addRemoteCandidate(candidate: RTCCandidate) {
        val handle = pc
        if (handle < 0) return
        val toFlush: List<RTCCandidate> = synchronized(negotiationLock) {
            if (!remoteDescriptionSet) {
                pendingCandidates += candidate
                null
            } else listOf(candidate)
        } ?: return
        memScoped { toFlush.forEach { c -> rtcAddRemoteCandidate(handle, c.candidate.cstr.ptr, c.mid.cstr.ptr) } }
    }

    private fun flushPendingCandidates() {
        val handle = pc
        if (handle < 0) return
        val snapshot = synchronized(negotiationLock) {
            if (pendingCandidates.isEmpty()) return
            pendingCandidates.toList().also { pendingCandidates.clear() }
        }
        memScoped { snapshot.forEach { c -> rtcAddRemoteCandidate(handle, c.candidate.cstr.ptr, c.mid.cstr.ptr) } }
    }

    public actual fun createDataChannel(
        label: String,
        protocol: String,
        config: RTCDataChannelConfig,
    ): RTCDataChannel {
        val handle = pc
        check(handle >= 0) { "PeerConnection closed" }
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
            rtcCreateDataChannelEx(handle, label.cstr.ptr, init.ptr)
        }
        check(dcId >= 0) { "Failed to create DataChannel" }
        val ch = RTCDataChannel(dcId, label, this, pcScope)
        putChannel(dcId, ch)
        return ch
    }

    internal fun putChannel(id: Int, ch: RTCDataChannel) {
        synchronized(channelsLock) { channels[id] = ch }
    }

    internal fun removeChannel(id: Int) {
        synchronized(channelsLock) { channels.remove(id) }
    }

    private fun snapshotChannels(): List<RTCDataChannel> =
        synchronized(channelsLock) { channels.values.toList() }

    public actual fun close() {
        val handle = synchronized(lifecycleLock) {
            val h = pc
            if (h < 0) return
            pc = -1
            h
        }
        snapshotChannels().forEach { it.close() }
        synchronized(negotiationLock) {
            pendingCandidates.clear()
            seenCandidates.clear()
        }
        remoteDescriptionSet = false
        offerSent = false
        answerSent = false
        lastLocalDescription = null
        nativeEvents.close()
        pcScope.cancel()
        rtcSetUserPointer(handle, null)
        selfRef?.dispose()
        selfRef = null
        rtcClosePeerConnection(handle)
        rtcDeletePeerConnection(handle)
        _connectionState.value = RTCConnectionState.CLOSED
    }

    private suspend fun consumeNativeEvents() {
        try {
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
                        val added = synchronized(negotiationLock) { seenCandidates.add(key) }
                        if (!added) continue
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
        } catch (_: CancellationException) {
        }
    }

    companion {
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
