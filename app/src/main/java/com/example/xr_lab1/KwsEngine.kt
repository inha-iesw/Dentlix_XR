package com.example.xr_lab1

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.BufferedInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.util.ArrayDeque
import java.util.HashMap
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class KwsEngine(
    private val context: Context,
    modelAssetPath: String = "model/kws_int8.tflite",
    private val triggerLabel: String = "zoom",
    private val triggerThreshold: Float = 0.6f,
    private val integrationMs: Long = 750L,
    private val refractoryMs: Long = 1000L,
    private val testWavAssetPath: String? = null,
    private val onKeyword: (label: String, score: Float) -> Unit,
) {
    private data class PosteriorSnapshot(
        val timeMs: Long,
        val scoresByLabel: Map<String, Float>
    )

    private val featureExtractor = KwsFeatureExtractor()
    private val detector = KwsTfliteDetector(context, modelAssetPath)
    private val running = AtomicBoolean(false)
    private val posteriorBuffer = ArrayDeque<PosteriorSnapshot>()

    private var worker: Thread? = null
    private var lastInferMs = 0L
    private var lastTriggerMs = 0L
    private var lastPerfLogMs = 0L

    fun start() {
        if (running.get()) return
        lastInferMs = 0L
        lastTriggerMs = 0L
        posteriorBuffer.clear()
        running.set(true)
        val loop = if (testWavAssetPath.isNullOrBlank()) ::loopMic else ::loopWavAssetRealtime
        worker = Thread(loop, "KwsEngine")
        worker?.start()
        if (testWavAssetPath.isNullOrBlank()) {
            Log.i(TAG, "KWS engine started (mic)")
        } else {
            Log.i(TAG, "KWS engine started (wav test): $testWavAssetPath")
        }
    }

    fun stop() {
        running.set(false)
        worker?.join(1500)
        worker = null
        Log.i(TAG, "KWS engine stopped")
    }

    fun close() {
        stop()
        detector.close()
    }

    private fun loopMic() {
        checkPermission()
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(HOP_SAMPLES * 8)

        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBuffer
        )
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord init failed")
            recorder.release()
            return
        }

        val ring = ShortArray(WINDOW_SAMPLES)
        var writePos = 0
        var filled = 0
        val chunk = ShortArray(HOP_SAMPLES)

        try {
            recorder.startRecording()
            while (running.get()) {
                val read = recorder.read(chunk, 0, chunk.size, AudioRecord.READ_BLOCKING)
                if (read <= 0) continue

                for (i in 0 until read) {
                    ring[writePos] = chunk[i]
                    writePos = (writePos + 1) % WINDOW_SAMPLES
                }
                filled = (filled + read).coerceAtMost(WINDOW_SAMPLES)
                if (filled < WINDOW_SAMPLES) continue

                val now = SystemClock.elapsedRealtime()
                if (now - lastInferMs < INFER_INTERVAL_MS) continue
                lastInferMs = now

                inferAndTrigger(ring, writePos, now)
            }
        } catch (e: Exception) {
            Log.e(TAG, "KWS loop error: ${e.message}", e)
        } finally {
            try {
                recorder.stop()
            } catch (_: Exception) {
            }
            recorder.release()
        }
    }

    private fun loopWavAssetRealtime() {
        val path = testWavAssetPath ?: return
        try {
            context.assets.open(path).use { raw ->
                val input = BufferedInputStream(raw)
                val header = parseWavHeader(input)
                if (header.channels != 1 || header.bitsPerSample != 16) {
                    Log.e(
                        TAG,
                        "WAV format must be PCM16 mono. actual channels=${header.channels} bits=${header.bitsPerSample}"
                    )
                    return
                }
                if (header.sampleRate != SAMPLE_RATE) {
                    Log.e(
                        TAG,
                        "WAV sample rate must be $SAMPLE_RATE. actual=${header.sampleRate}"
                    )
                    return
                }
                var remainingDataBytes = header.dataBytes.toLong()
                Log.i(
                    TAG,
                    "WAV test start: length=${formatSeconds((header.dataBytes / 2.0) / SAMPLE_RATE)}s"
                )

                val ring = ShortArray(WINDOW_SAMPLES)
                var writePos = 0
                var filled = 0
                val chunk = ShortArray(HOP_SAMPLES)
                val startMs = SystemClock.elapsedRealtime()
                var playedSamples = 0L

                while (running.get() && remainingDataBytes >= 2L) {
                    val maxSamples = minOf(chunk.size, (remainingDataBytes / 2L).toInt())
                    val read = readPcm16(input, chunk, 0, maxSamples)
                    if (read <= 0) {
                        Log.i(TAG, "WAV test finished: reached EOF")
                        break
                    }
                    remainingDataBytes -= (read * 2L)

                    for (i in 0 until read) {
                        ring[writePos] = chunk[i]
                        writePos = (writePos + 1) % WINDOW_SAMPLES
                    }
                    filled = (filled + read).coerceAtMost(WINDOW_SAMPLES)
                    playedSamples += read.toLong()

                    val targetElapsedMs = (playedSamples * 1000L) / SAMPLE_RATE
                    val nowElapsedMs = SystemClock.elapsedRealtime() - startMs
                    val sleepMs = targetElapsedMs - nowElapsedMs
                    if (sleepMs > 1L) {
                        SystemClock.sleep(sleepMs)
                    }

                    if (filled < WINDOW_SAMPLES) continue
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastInferMs < INFER_INTERVAL_MS) continue
                    lastInferMs = now
                    val triggered = inferAndTrigger(ring, writePos, now)
                    if (triggered) {
                        val wavSec = playedSamples.toDouble() / SAMPLE_RATE.toDouble()
                        Log.i(TAG, "Triggered at wav=${formatSeconds(wavSec)}s")
                    }
                }
                Log.i(
                    TAG,
                    "WAV test finished: played=${formatSeconds(playedSamples.toDouble() / SAMPLE_RATE)}s"
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "KWS wav test loop error: ${e.message}", e)
        } finally {
            running.set(false)
        }
    }

    private fun inferAndTrigger(ring: ShortArray, writePos: Int, nowMs: Long): Boolean {
        val t0 = SystemClock.elapsedRealtimeNanos()
        val window = snapshotWindow(ring, writePos)
        val t1 = SystemClock.elapsedRealtimeNanos()
        val feature = featureExtractor.extract(window)
        val t2 = SystemClock.elapsedRealtimeNanos()
        val result = detector.run(feature, N_MELS, featureExtractor.timeFrames)
        val t3 = SystemClock.elapsedRealtimeNanos()
        updatePosteriorBuffer(nowMs, result.scoresByLabel)
        val averagedScores = computeAveragedScores()
        val triggerScore = averagedScores[triggerLabel] ?: 0f

        if (nowMs - lastPerfLogMs >= 1000L) {
            val windowMs = (t1 - t0) / 1_000_000.0
            val featureMs = (t2 - t1) / 1_000_000.0
            val modelMs = (t3 - t2) / 1_000_000.0
            val totalMs = (t3 - t0) / 1_000_000.0
            Log.i(
                TAG,
                "KWS infer perf: total=${formatMillis(totalMs)}ms " +
                    "(window=${formatMillis(windowMs)}ms, " +
                    "feature=${formatMillis(featureMs)}ms, " +
                    "model=${formatMillis(modelMs)}ms), score=$triggerScore"
            )
            lastPerfLogMs = nowMs
        }

        if (triggerScore >= triggerThreshold && (nowMs - lastTriggerMs) >= refractoryMs) {
            lastTriggerMs = nowMs
            onKeyword(triggerLabel, triggerScore)
            Log.i(TAG, "Triggered: $triggerLabel score=$triggerScore")
            return true
        }
        return false
    }

    private fun updatePosteriorBuffer(nowMs: Long, scoresByLabel: Map<String, Float>) {
        posteriorBuffer.addLast(
            PosteriorSnapshot(
                timeMs = nowMs,
                scoresByLabel = HashMap(scoresByLabel)
            )
        )
        val minTime = nowMs - integrationMs
        while (posteriorBuffer.isNotEmpty()) {
            val first = posteriorBuffer.peekFirst() ?: break
            if (first.timeMs >= minTime) break
            posteriorBuffer.removeFirst()
        }
    }

    // 스냅샷 prob 평균 구하기 
    private fun computeAveragedScores(): Map<String, Float> {
        if (posteriorBuffer.isEmpty()) return emptyMap()

        val sums = HashMap<String, Float>()
        for (snapshot in posteriorBuffer) {
            for ((label, score) in snapshot.scoresByLabel) {
                sums[label] = (sums[label] ?: 0f) + score
            }
        }
        val n = posteriorBuffer.size.toFloat()
        return sums.mapValues { (_, sum) -> sum / n }
    }

    private data class WavHeader(
        val sampleRate: Int,
        val channels: Int,
        val bitsPerSample: Int,
        val dataBytes: Int
    )

    private fun parseWavHeader(input: InputStream): WavHeader {
        val riff = ByteArray(12)
        readFully(input, riff, 0, riff.size)
        if (!riff.copyOfRange(0, 4).contentEquals(byteArrayOf('R'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 'F'.code.toByte()))) {
            throw IOException("Invalid WAV header: missing RIFF")
        }
        if (!riff.copyOfRange(8, 12).contentEquals(byteArrayOf('W'.code.toByte(), 'A'.code.toByte(), 'V'.code.toByte(), 'E'.code.toByte()))) {
            throw IOException("Invalid WAV header: missing WAVE")
        }

        var sampleRate = 0
        var channels = 0
        var bitsPerSample = 0
        var dataBytes = 0
        var foundFmt = false
        var foundData = false

        while (!foundData) {
            val chunkHeader = ByteArray(8)
            readFully(input, chunkHeader, 0, chunkHeader.size)
            val chunkId = String(chunkHeader, 0, 4, Charsets.US_ASCII)
            val chunkSize = leInt(chunkHeader, 4)
            when (chunkId) {
                "fmt " -> {
                    val fmt = ByteArray(chunkSize)
                    readFully(input, fmt, 0, fmt.size)
                    if (fmt.size < 16) {
                        throw IOException("Invalid WAV fmt chunk size: ${fmt.size}")
                    }
                    val audioFormat = leShort(fmt, 0)
                    channels = leShort(fmt, 2)
                    sampleRate = leInt(fmt, 4)
                    bitsPerSample = leShort(fmt, 14)
                    if (audioFormat != 1) {
                        throw IOException("Unsupported WAV format: $audioFormat (only PCM=1)")
                    }
                    foundFmt = true
                }
                "data" -> {
                    if (!foundFmt) {
                        throw IOException("Invalid WAV: data before fmt")
                    }
                    dataBytes = chunkSize
                    foundData = true
                }
                else -> {
                    skipFully(input, chunkSize.toLong())
                }
            }
            if (chunkSize % 2 == 1) {
                skipFully(input, 1)
            }
        }

        return WavHeader(
            sampleRate = sampleRate,
            channels = channels,
            bitsPerSample = bitsPerSample,
            dataBytes = dataBytes
        )
    }

    private fun readPcm16(input: InputStream, out: ShortArray, offset: Int, count: Int): Int {
        val bytesToRead = count * 2
        val bytes = ByteArray(bytesToRead)
        var total = 0
        while (total < bytesToRead) {
            val n = input.read(bytes, total, bytesToRead - total)
            if (n <= 0) break
            total += n
        }
        if (total < 2) return -1
        val samples = total / 2
        for (i in 0 until samples) {
            val lo = bytes[i * 2].toInt() and 0xFF
            val hi = bytes[i * 2 + 1].toInt()
            out[offset + i] = ((hi shl 8) or lo).toShort()
        }
        return samples
    }

    private fun readFully(input: InputStream, buffer: ByteArray, offset: Int, length: Int) {
        var read = 0
        while (read < length) {
            val n = input.read(buffer, offset + read, length - read)
            if (n < 0) throw EOFException("Unexpected EOF while reading WAV")
            read += n
        }
    }

    private fun skipFully(input: InputStream, bytes: Long) {
        var remain = bytes
        while (remain > 0) {
            val skipped = input.skip(remain)
            if (skipped <= 0) {
                if (input.read() == -1) throw EOFException("Unexpected EOF while skipping WAV chunk")
                remain--
            } else {
                remain -= skipped
            }
        }
    }

    private fun leInt(data: ByteArray, offset: Int): Int {
        return (data[offset].toInt() and 0xFF) or
            ((data[offset + 1].toInt() and 0xFF) shl 8) or
            ((data[offset + 2].toInt() and 0xFF) shl 16) or
            ((data[offset + 3].toInt() and 0xFF) shl 24)
    }

    private fun leShort(data: ByteArray, offset: Int): Int {
        return (data[offset].toInt() and 0xFF) or
            ((data[offset + 1].toInt() and 0xFF) shl 8)
    }

    private fun formatSeconds(seconds: Double): String {
        return String.format(Locale.US, "%.3f", seconds)
    }

    private fun formatMillis(ms: Double): String {
        return String.format(Locale.US, "%.2f", ms)
    }

    private fun snapshotWindow(ring: ShortArray, writePos: Int): ShortArray {
        val out = ShortArray(WINDOW_SAMPLES)
        var src = writePos
        for (i in 0 until WINDOW_SAMPLES) {
            out[i] = ring[src]
            src++
            if (src >= WINDOW_SAMPLES) src = 0
        }
        return out
    }

    private fun checkPermission() {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        require(granted) { "RECORD_AUDIO permission not granted" }
    }

    companion object {
        private const val TAG = "XR_KWS"
        private const val SAMPLE_RATE = 16_000
        private const val WINDOW_SAMPLES = 16_000
        private const val HOP_SAMPLES = 160
        private const val INFER_INTERVAL_MS = 100L
        private const val N_MELS = 40
    }
}

