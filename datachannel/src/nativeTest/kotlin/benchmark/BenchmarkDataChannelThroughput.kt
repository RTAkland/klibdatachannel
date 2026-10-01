/*
 * Copyright © 2026 RTAkland
 * Author: RTAkland
 * Date: 2026-10-01
 */


@file:OptIn(ExperimentalForeignApi::class, NativeRuntimeApi::class)

package benchmark

import cn.rtast.webrtc.RTCPeerConnectionFactory
import cn.rtast.webrtc.configuration.RTCLogLevel
import cn.rtast.webrtc.configuration.rtcConfiguration
import cn.rtast.webrtc.state.RTCDataChannelState
import kotlinx.cinterop.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import libdatachannel.rtcSctpSettings
import libdatachannel.rtcSetSctpSettings
import platform.posix.*
import test.LoopbackSignaling
import kotlin.concurrent.Volatile
import kotlin.native.runtime.GC
import kotlin.native.runtime.NativeRuntimeApi
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlin.time.measureTime

class BenchmarkDataChannelFileTransfer {
    private val totalBytes = 34281976L
    private val chunkSize = 128 * 1024
    private val backpressureLimit = 8L * 1024 * 1024
    private val progressInterval = 100.milliseconds

    private val srcPath = "/dev/shm/webrtc-bench-src.bin"
    private val dstPath = "/tmp/12312313213412413"

    @Volatile
    private var sentBytes: Long = 0L

    @Volatile
    private var recvBytes: Long = 0L

    @Test
    fun benchmarkFileTransfer() = runBlocking {
        RTCPeerConnectionFactory.init(RTCLogLevel.DISABLED)
        memScoped {
            val settings = alloc<rtcSctpSettings>()
            settings.recvBufferSize = 16 * 1024 * 1024
            settings.sendBufferSize = 16 * 1024 * 1024
            settings.maxChunksOnQueue = 4096
            settings.initialCongestionWindow = 64
            settings.maxBurst = 32
            rtcSetSctpSettings(settings.ptr)
        }

        val existing = fileSize(srcPath)
        check(existing == totalBytes) {
            "source file $srcPath size=$existing, expected=$totalBytes"
        }

        removeFile(dstPath)
        val rootScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            GC.collect()
            runSingle(rootScope)
        } finally {
            rootScope.cancel()
            RTCPeerConnectionFactory.cleanup()
        }
    }

    private suspend fun runSingle(parentScope: CoroutineScope) {
        val runScope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))
        try {
            val sigA = LoopbackSignaling()
            val sigB = LoopbackSignaling()
            sigA.peer = sigB
            sigB.peer = sigA

            val config = rtcConfiguration {}
            val pcA = RTCPeerConnectionFactory.createPeerConnection(runScope, config)
            val pcB = RTCPeerConnectionFactory.createPeerConnection(runScope, config)

            pcA.localDescriptions.onEach { sigA.sendSdp(it) }.launchIn(runScope)
            pcA.localCandidates.onEach { sigA.sendCandidate(it) }.launchIn(runScope)
            sigA.remoteDescriptions.onEach { pcA.setRemoteDescription(it.sdp, it.type) }.launchIn(runScope)
            sigA.remoteCandidates.onEach { pcA.addRemoteCandidate(it) }.launchIn(runScope)
            pcB.localDescriptions.onEach { sigB.sendSdp(it) }.launchIn(runScope)
            pcB.localCandidates.onEach { sigB.sendCandidate(it) }.launchIn(runScope)
            sigB.remoteDescriptions.onEach { sd ->
                pcB.setRemoteDescription(sd.sdp, sd.type)
                if (sd.type == "offer") pcB.createAnswer()
            }.launchIn(runScope)
            sigB.remoteCandidates.onEach { pcB.addRemoteCandidate(it) }.launchIn(runScope)

            val receivedTarget = CompletableDeferred<Unit>()
            val writeChannel = Channel<ByteArray>(Channel.UNLIMITED)

            pcB.incomingDataChannels.take(1).onEach { dc ->
                dc.messages.bytes.collect { bytes ->
                    writeChannel.trySend(bytes)
                    val r = recvBytes + bytes.size
                    recvBytes = r
                    if (r >= totalBytes && !receivedTarget.isCompleted) {
                        receivedTarget.complete(Unit)
                    }
                }
            }.launchIn(runScope)

            val writerJob = runScope.launch {
                val dst = fopen(dstPath, "wb") ?: error("Cannot open $dstPath")
                try {
                    for (bytes in writeChannel) bytes.usePinned { pinned ->
                        fwrite(pinned.addressOf(0), 1u, bytes.size.convert(), dst)
                    }
                } finally {
                    fflush(dst)
                    fclose(dst)
                }
            }

            val dcA = pcA.createDataChannel("bench")
            dcA.state.first { it == RTCDataChannelState.Open }
            val totalMb = (totalBytes / 1024 / 1024).toInt()
            println("file transfer: chunk=${chunkSize / 1024} KB, total=${totalMb} MB")
            val start = TimeSource.Monotonic.markNow()
            val renderJob = launchRenderer(runScope, start, totalBytes)
            val elapsed = measureTime {
                val src = fopen(srcPath, "rb") ?: error("Cannot open $srcPath")
                try {
                    val buffer = ByteArray(chunkSize)
                    while (sentBytes < totalBytes) {
                        val n = buffer.usePinned { pinned ->
                            fread(pinned.addressOf(0), 1u, chunkSize.convert(), src).toInt()
                        }
                        if (n <= 0) break
                        while (dcA.bufferedAmount.value > backpressureLimit) {
                            delay(1.milliseconds)
                        }

                        if (n == chunkSize) {
                            dcA.send(buffer)
                        } else {
                            dcA.send(buffer.copyOf(n))
                        }
                        sentBytes += n
                    }
                } finally {
                    fclose(src)
                }

                withTimeout(180.seconds) { receivedTarget.await() }
                writeChannel.close()
                withTimeout(30.seconds) { writerJob.join() }
            }

            renderJob.cancelAndJoin()
            renderProgress(sentBytes, recvBytes, totalBytes, start.elapsedNow())
            println()
            val seconds = elapsed.inWholeMilliseconds / 1000.0
            val s = sentBytes
            val r = recvBytes
            val sendMbps = if (seconds > 0) (s / 1024.0 / 1024.0) / seconds else 0.0
            val recvMbps = if (seconds > 0) (r / 1024.0 / 1024.0) / seconds else 0.0

            println(
                "sent=${s / 1024 / 1024} MB  recv=${r / 1024 / 1024} MB  " +
                        "time=${seconds.format(3)} s  " +
                        "send=${sendMbps.format(1)} MB/s  recv=${recvMbps.format(1)} MB/s"
            )

            val dstSize = fileSize(dstPath)
            println(
                "dst size=$dstSize bytes (${dstSize / 1024 / 1024} MB)  " +
                        "expected=$totalBytes  match=${dstSize == totalBytes}"
            )
            dcA.close()
            pcA.close()
            pcB.close()
            sigA.close()
            sigB.close()
        } finally {
            runScope.cancel()
        }
    }

    private fun launchRenderer(scope: CoroutineScope, start: TimeSource.Monotonic.ValueTimeMark, total: Long): Job =
        scope.launch {
            printf("\n\n")
            fflush(stdout)
            while (isActive) {
                renderProgress(sentBytes, recvBytes, total, start.elapsedNow())
                delay(progressInterval)
            }
        }

    private fun renderProgress(sent: Long, recv: Long, total: Long, elapsed: Duration) {
        val seconds = elapsed.inWholeMilliseconds / 1000.0
        val sendMbps = if (seconds > 0) (sent / 1024.0 / 1024.0) / seconds else 0.0
        val recvMbps = if (seconds > 0) (recv / 1024.0 / 1024.0) / seconds else 0.0

        val sentMb = (sent / 1024 / 1024).toInt()
        val recvMb = (recv / 1024 / 1024).toInt()
        val totalMb = (total / 1024 / 1024).toInt()

        printf("\u001B[2A")
        printf(
            "[send] [%s] %3d%% (%4d/%4d MB) %6.0f MB/s\n",
            makeBar(sent, total), sentMb * 100 / totalMb.coerceAtLeast(1),
            sentMb, totalMb, sendMbps
        )
        printf(
            "[recv] [%s] %3d%% (%4d/%4d MB) %6.0f MB/s\n",
            makeBar(recv, total), recvMb * 100 / totalMb.coerceAtLeast(1),
            recvMb, totalMb, recvMbps
        )
        fflush(stdout)
    }

    private fun makeBar(current: Long, total: Long): String {
        val barLength = 30
        val ratio = current.toDouble() / total
        val filled = (ratio * barLength).toInt().coerceIn(0, barLength)
        return "#".repeat(filled) + "-".repeat(barLength - filled)
    }

    private fun Double.format(decimals: Int): String {
        val factor = when (decimals) {
            1 -> 10.0
            2 -> 100.0
            3 -> 1000.0
            else -> 1.0
        }
        val rounded = kotlin.math.round(this * factor) / factor
        return rounded.toString()
    }

    private fun fileSize(path: String): Long {
        val f = fopen(path, "rb") ?: return -1
        return try {
            fseek(f, 0, SEEK_END)
            ftell(f)
        } finally {
            fclose(f)
        }
    }

    private fun removeFile(path: String) {
        memScoped { remove(path.cstr.ptr) }
    }
}