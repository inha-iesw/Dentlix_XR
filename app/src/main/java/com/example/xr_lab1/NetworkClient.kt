package com.example.xr_lab1

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit

class NetworkClient {
    private val serverUrl = "wss://ws.dentlixr.store/ws"

    private val httpClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()
    private var webSocket: WebSocket? = null

    data class MaskFrame(
        val width: Int,
        val height: Int,
        val bytes: ByteArray,
        val frameId: Int,
        val sendTsMs: Long
    )

    private val _maskFlow = MutableSharedFlow<MaskFrame>(replay = 1, extraBufferCapacity = 1)
    val maskFlow = _maskFlow.asSharedFlow()

    suspend fun connect() {
        withContext(Dispatchers.IO) {
            try {
                if (webSocket != null) return@withContext

                Log.d("XR_LAB", "WebSocket connect start ($serverUrl)")
                val request = Request.Builder()
                    .url(serverUrl)
                    .build()
                webSocket = httpClient.newWebSocket(request, MaskWebSocketListener())
            } catch (e: Exception) {
                Log.e("XR_LAB", "WebSocket connect failed: ${e.message}")
                close()
            }
        }
    }

    suspend fun sendFrame(jpegData: ByteArray, frameId: Int, sendTsMs: Long) {
        withContext(Dispatchers.IO) {
            try {
                if (webSocket == null) {
                    connect()
                }

                val socket = webSocket ?: return@withContext
                val header = ByteBuffer.allocate(12)
                header.putInt(frameId)
                header.putLong(sendTsMs)
                val payload = (header.array() + jpegData).toByteString()
                if (!socket.send(payload)) {
                    Log.e("XR_LAB", "WebSocket send failed")
                }
            } catch (e: Exception) {
                Log.e("XR_LAB", "Send error: ${e.message}")
                close()
            }
        }
    }

    fun startMaskReceiver(scope: CoroutineScope) {
        scope.launch {
            readMaskLoop()
        }
    }

    private suspend fun readMaskLoop() {
        // WebSocket listener handles mask frames.
    }

    fun close() {
        try {
            webSocket?.close(1000, "client closing")
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            webSocket = null
        }
    }

    companion object {
        private const val MAX_MASK_BYTES = 1920 * 1080
    }

    private inner class MaskWebSocketListener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.d("XR_LAB", "WebSocket connected")
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            try {
                val payload = bytes.toByteArray()
                if (payload.size < 21) {
                    Log.e("XR_LAB", "Mask packet too small: ${payload.size}")
                    return
                }
                val buffer = ByteBuffer.wrap(payload)
                val width = buffer.int
                val height = buffer.int
                val format = buffer.get().toInt()
                val frameId = buffer.int
                val sendTsMs = buffer.long
                if (width <= 0 || height <= 0) return
                if (format != 0) {
                    Log.e("XR_LAB", "Unsupported mask format: $format")
                    return
                }
                val size = width * height
                if (size <= 0 || size > MAX_MASK_BYTES) {
                    Log.e("XR_LAB", "Mask size too large: $size")
                    return
                }
                if (payload.size < 21 + size) {
                    Log.e("XR_LAB", "Mask payload incomplete: ${payload.size} < ${21 + size}")
                    return
                }
                val data = ByteArray(size)
                buffer.get(data)
                val now = android.os.SystemClock.elapsedRealtime()
                val latency = now - sendTsMs
                Log.d("XR_LAB", "Mask latency: ${latency}ms frameId=$frameId")
                _maskFlow.tryEmit(MaskFrame(width, height, data, frameId, sendTsMs))
            } catch (e: Exception) {
                Log.e("XR_LAB", "Mask receive error: ${e.message}")
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.e("XR_LAB", "WebSocket failure: ${t.message}")
            close()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.d("XR_LAB", "WebSocket closed: $code $reason")
            close()
        }
    }
}
