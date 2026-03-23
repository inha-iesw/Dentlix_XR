package com.example.xr_lab1

import android.content.Context
import android.util.Log
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder

class KwsTfliteDetector(
    context: Context,
    modelAssetPath: String = "model/model_kws_v3_int8.tflite",
    private val labels: List<String> = listOf("zoom", "unknown", "silence"),
) {
    enum class InputLayout { NCHW, NHWC }

    data class InputStats(
        val min: Float,
        val max: Float,
        val mean: Float,
        val clippedRatio: Float
    )

    data class Result(
        val label: String,
        val score: Float,
        val scoresByLabel: Map<String, Float>,
        val inputStats: InputStats
    )

    private val interpreter: Interpreter
    private val inputShape: IntArray
    private val outputShape: IntArray
    private val inputScale: Float
    private val inputZeroPoint: Int
    private val outputScale: Float
    private val outputZeroPoint: Int
    private val inputLayout: InputLayout
    private val inputBuffer: ByteBuffer
    private val outputBuffer: ByteBuffer
    private val inputElements: Int
    private val outputElements: Int

    init {
        val options = Interpreter.Options().apply {
            setNumThreads(2)
            setUseXNNPACK(true)
        }
        interpreter = Interpreter(loadModel(context, modelAssetPath), options)
        inputShape = interpreter.getInputTensor(0).shape()
        outputShape = interpreter.getOutputTensor(0).shape()

        val inputTensor = interpreter.getInputTensor(0)
        val outputTensor = interpreter.getOutputTensor(0)
        require(inputTensor.dataType() == DataType.INT8) { "Expected INT8 input tensor" }
        require(outputTensor.dataType() == DataType.INT8) { "Expected INT8 output tensor" }

        val inQ = inputTensor.quantizationParams()
        inputScale = inQ.scale
        inputZeroPoint = inQ.zeroPoint
        val outQ = outputTensor.quantizationParams()
        outputScale = outQ.scale
        outputZeroPoint = outQ.zeroPoint

        inputElements = inputShape.fold(1) { acc, d -> acc * d }
        outputElements = outputShape.fold(1) { acc, d -> acc * d }
        require(outputElements >= labels.size) {
            "Output elements ($outputElements) < labels (${labels.size})"
        }
        inputBuffer = ByteBuffer.allocateDirect(inputElements).order(ByteOrder.nativeOrder())
        outputBuffer = ByteBuffer.allocateDirect(outputElements).order(ByteOrder.nativeOrder())

        inputLayout = resolveInputLayout(inputShape)
        Log.i(
            TAG,
            "Loaded KWS model: input=${inputShape.contentToString()} layout=$inputLayout " +
                "inQ(scale=$inputScale,zp=$inputZeroPoint), output=${outputShape.contentToString()} " +
                "outQ(scale=$outputScale,zp=$outputZeroPoint)"
        )
    }

    fun run(featureNMelByTime: FloatArray, nMels: Int, timeFrames: Int): Result {
        val inputStats = encodeInput(featureNMelByTime, nMels, timeFrames)
        outputBuffer.rewind()
        interpreter.run(inputBuffer, outputBuffer)

        val rawScores = FloatArray(labels.size)
        outputBuffer.rewind()
        for (i in rawScores.indices) {
            val q = outputBuffer.get().toInt()
            rawScores[i] = (q - outputZeroPoint) * outputScale
        }
        val probs = softmax(rawScores)

        var best = 0
        for (i in 1 until probs.size) {
            if (probs[i] > probs[best]) best = i
        }
        val scoreMap = labels.indices.associate { i -> labels[i] to probs[i] }
        return Result(labels[best], probs[best], scoreMap, inputStats)
    }

    fun close() {
        interpreter.close()
    }

    private fun encodeInput(feature: FloatArray, nMels: Int, timeFrames: Int): InputStats {
        require(feature.size == nMels * timeFrames)
        inputBuffer.rewind()

        var min = Float.POSITIVE_INFINITY
        var max = Float.NEGATIVE_INFINITY
        var sum = 0.0
        var clipped = 0

        when (inputLayout) {
            InputLayout.NCHW -> {
                // [1,1,nMels,time] or [1,1,time,nMels]
                val melFirst = inputShape[2] == nMels && inputShape[3] == timeFrames
                if (melFirst) {
                    for (m in 0 until nMels) {
                        for (t in 0 until timeFrames) {
                            val v = feature[m * timeFrames + t]
                            if (v < min) min = v
                            if (v > max) max = v
                            sum += v.toDouble()
                            if (isClipped(v)) clipped++
                            inputBuffer.put(floatToInt8(v))
                        }
                    }
                } else {
                    for (t in 0 until timeFrames) {
                        for (m in 0 until nMels) {
                            val v = feature[m * timeFrames + t]
                            if (v < min) min = v
                            if (v > max) max = v
                            sum += v.toDouble()
                            if (isClipped(v)) clipped++
                            inputBuffer.put(floatToInt8(v))
                        }
                    }
                }
            }
            InputLayout.NHWC -> {
                // [1,nMels,time,1] or [1,time,nMels,1]
                val melFirst = inputShape[1] == nMels && inputShape[2] == timeFrames
                if (melFirst) {
                    for (m in 0 until nMels) {
                        for (t in 0 until timeFrames) {
                            val v = feature[m * timeFrames + t]
                            if (v < min) min = v
                            if (v > max) max = v
                            sum += v.toDouble()
                            if (isClipped(v)) clipped++
                            inputBuffer.put(floatToInt8(v))
                        }
                    }
                } else {
                    for (t in 0 until timeFrames) {
                        for (m in 0 until nMels) {
                            val v = feature[m * timeFrames + t]
                            if (v < min) min = v
                            if (v > max) max = v
                            sum += v.toDouble()
                            if (isClipped(v)) clipped++
                            inputBuffer.put(floatToInt8(v))
                        }
                    }
                }
            }
        }
        inputBuffer.rewind()
        val count = feature.size.coerceAtLeast(1)
        return InputStats(
            min = min,
            max = max,
            mean = (sum / count.toDouble()).toFloat(),
            clippedRatio = clipped.toFloat() / count.toFloat()
        )
    }

    private fun floatToInt8(v: Float): Byte {
        val q = (v / inputScale + inputZeroPoint).toInt().coerceIn(-128, 127)
        return q.toByte()
    }

    private fun isClipped(v: Float): Boolean {
        val q = (v / inputScale + inputZeroPoint).toInt()
        return q < -128 || q > 127
    }

    private fun resolveInputLayout(shape: IntArray): InputLayout {
        require(shape.size == 4) { "Expected 4D input tensor, got ${shape.contentToString()}" }
        if (shape[0] != 1) {
            Log.w(TAG, "Unexpected batch dimension: ${shape[0]}")
        }
        return if (shape[1] == 1) InputLayout.NCHW else InputLayout.NHWC
    }

    private fun softmax(logits: FloatArray): FloatArray {
        var max = logits[0]
        for (i in 1 until logits.size) {
            if (logits[i] > max) max = logits[i]
        }
        var sum = 0.0
        val exps = DoubleArray(logits.size)
        for (i in logits.indices) {
            val e = kotlin.math.exp((logits[i] - max).toDouble())
            exps[i] = e
            sum += e
        }
        return FloatArray(logits.size) { i -> (exps[i] / sum).toFloat() }
    }

    private fun loadModel(context: Context, assetPath: String): ByteBuffer {
        context.assets.openFd(assetPath).use { afd ->
            afd.createInputStream().use { input ->
                val channel = input.channel
                return channel.map(
                    java.nio.channels.FileChannel.MapMode.READ_ONLY,
                    afd.startOffset,
                    afd.declaredLength
                )
            }
        }
    }

    companion object {
        private const val TAG = "XR_KWS"
    }
}
