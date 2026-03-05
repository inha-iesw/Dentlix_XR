package com.example.xr_lab1

import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

class KwsFeatureExtractor(
    private val sampleRate: Int = 16_000,
    private val nFft: Int = 400,
    private val hop: Int = 160,
    private val nMels: Int = 40,
    private val eps: Float = 1e-6f
) {
    private val fftBins = nFft / 2 + 1
    private val pad = nFft / 2
    private val hann = FloatArray(nFft) { i ->
        (0.5 - 0.5 * cos((2.0 * Math.PI * i) / nFft)).toFloat()
    }
    private val cosTable = Array(fftBins) { k ->
        FloatArray(nFft) { n ->
            cos((2.0 * Math.PI * k * n) / nFft).toFloat()
        }
    }
    private val sinTable = Array(fftBins) { k ->
        FloatArray(nFft) { n ->
            sin((2.0 * Math.PI * k * n) / nFft).toFloat()
        }
    }
    private val melFilterBank = buildMelFilterBank()
    val timeFrames: Int = ((16_000 + 2 * pad - nFft) / hop) + 1

    fun extract(pcm16: ShortArray): FloatArray {
        require(pcm16.size == 16_000) { "Expected 1.0s / 16000 samples, got ${pcm16.size}" }
        val signal = FloatArray(16_000 + 2 * pad)
        for (i in pcm16.indices) {
            signal[i + pad] = pcm16[i] / 32768.0f
        }

        val out = FloatArray(nMels * timeFrames)
        val frame = FloatArray(nFft)
        val power = FloatArray(fftBins)
        val mel = FloatArray(nMels)

        for (t in 0 until timeFrames) {
            val base = t * hop
            for (i in 0 until nFft) {
                frame[i] = signal[base + i] * hann[i]
            }
            stftPower(frame, power)

            for (m in 0 until nMels) {
                var sum = 0f
                val filter = melFilterBank[m]
                for (k in 0 until fftBins) {
                    sum += filter[k] * power[k]
                }
                mel[m] = powerToDb(sum)
            }
            for (m in 0 until nMels) {
                out[m * timeFrames + t] = mel[m]
            }
        }

        // per-clip z-norm
        var mean = 0.0
        for (v in out) mean += v
        mean /= out.size.toDouble()
        var varSum = 0.0
        for (v in out) {
            val d = v - mean
            varSum += d * d
        }
        val std = sqrt(varSum / out.size + eps.toDouble()).toFloat()
        for (i in out.indices) {
            out[i] = ((out[i] - mean.toFloat()) / std)
        }
        return out
    }

    private fun stftPower(frame: FloatArray, outPower: FloatArray) {
        for (k in 0 until fftBins) {
            var re = 0f
            var im = 0f
            val c = cosTable[k]
            val s = sinTable[k]
            for (n in 0 until nFft) {
                val x = frame[n]
                re += x * c[n]
                im -= x * s[n]
            }
            outPower[k] = re * re + im * im
        }
    }

    private fun powerToDb(power: Float): Float {
        val safe = max(power, 1e-10f)
        return (10f * (ln(safe.toDouble()) / ln(10.0))).toFloat()
    }

    private fun buildMelFilterBank(): Array<FloatArray> {
        val fMin = 0.0
        val fMax = sampleRate / 2.0
        val melMin = hzToMel(fMin)
        val melMax = hzToMel(fMax)
        val melPoints = DoubleArray(nMels + 2) { i ->
            melMin + (melMax - melMin) * i / (nMels + 1)
        }
        val hzPoints = DoubleArray(nMels + 2) { i -> melToHz(melPoints[i]) }
        val bins = IntArray(nMels + 2) { i ->
            ((nFft + 1) * hzPoints[i] / sampleRate).toInt().coerceIn(0, fftBins - 1)
        }
        return Array(nMels) { m ->
            val fbank = FloatArray(fftBins)
            val left = bins[m]
            val center = bins[m + 1]
            val right = bins[m + 2]
            for (k in left until center) {
                val denom = max(1, center - left).toFloat()
                fbank[k] = (k - left) / denom
            }
            for (k in center until right) {
                val denom = max(1, right - center).toFloat()
                fbank[k] = (right - k) / denom
            }
            fbank
        }
    }

    private fun hzToMel(hz: Double): Double = 2595.0 * (1.0 + hz / 700.0).let { ln(it) / ln(10.0) }
    private fun melToHz(mel: Double): Double = 700.0 * (10.0.pow(mel / 2595.0) - 1.0)
}
