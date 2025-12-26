package com.example.xr_lab1

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer

class NetworkClient {
    // ADB 포트 포워딩을 사용하므로 127.0.0.1 사용 (또는 실제 서버 IP)
    private val SERVER_IP = "0.tcp.jp.ngrok.io" // 사용하는 주소로 변경 확인
    private val SERVER_PORT = 15464          // 사용하는 포트로 변경 확인

    private var socket: Socket? = null
    private var outputStream: DataOutputStream? = null
    private var inputStream: DataInputStream? = null

    data class MaskFrame(val width: Int, val height: Int, val bytes: ByteArray)

    private val _maskFlow = MutableSharedFlow<MaskFrame>(replay = 1, extraBufferCapacity = 1)
    val maskFlow = _maskFlow.asSharedFlow()

    // 연결 함수
    suspend fun connect() {
        withContext(Dispatchers.IO) {
            try {
                if (socket != null && !socket!!.isClosed && socket!!.isConnected) return@withContext

                Log.d("XR_LAB", "🔄 서버 연결 시도 중... ($SERVER_IP:$SERVER_PORT)")
                socket = Socket(SERVER_IP, SERVER_PORT)
                socket?.tcpNoDelay = true // 딜레이 없이 즉시 전송 옵션
                socket?.soTimeout = 30000  // Mask inference can be slow; tolerate long reads
                outputStream = DataOutputStream(socket!!.getOutputStream())
                inputStream = DataInputStream(socket!!.getInputStream())
                Log.d("XR_LAB", "✅ 서버 연결 성공!")
            } catch (e: Exception) {
                Log.e("XR_LAB", "❌ 연결 실패: ${e.message}")
                close()
            }
        }
    }

    // 전송 함수 (수정됨: 버퍼 병합 전송)
    suspend fun sendFrame(jpegData: ByteArray) {
        withContext(Dispatchers.IO) {
            try {
                if (socket == null || socket!!.isClosed) {
                    connect()
                }

                // 소켓이 여전히 없으면 포기
                if (socket == null || socket!!.isClosed) return@withContext

                outputStream?.let { stream ->
                    val size = jpegData.size

                    // [핵심 수정] 헤더(4byte) + 바디(이미지)를 하나의 배열로 합침
                    val buffer = ByteBuffer.allocate(4 + size)
                    buffer.putInt(size)   // 길이 기록
                    buffer.put(jpegData)  // 이미지 기록

                    val combinedData = buffer.array()

                    // 한 번에 전송 (Write once)
                    stream.write(combinedData)
                    stream.flush() // 즉시 밀어내기
                }
            } catch (e: Exception) {
                // Broken pipe가 나면 여기서 잡힘
                Log.e("XR_LAB", "❌ 전송 중 에러 (Broken Pipe 등): ${e.message}")
                close() // 소켓을 닫아줘야 다음 프레임에서 깨끗하게 재연결함
            }
        }
    }

    fun startMaskReceiver(scope: CoroutineScope) {
        scope.launch {
            readMaskLoop()
        }
    }

    private suspend fun readMaskLoop() {
        withContext(Dispatchers.IO) {
            while (true) {
                val input = inputStream ?: return@withContext
                try {
                    val width = input.readInt()
                    val height = input.readInt()
                    val format = input.readByte().toInt()
                    if (width <= 0 || height <= 0) return@withContext
                    if (format != 0) {
                        Log.e("XR_LAB", "Unsupported mask format: $format")
                        return@withContext
                    }
                    val size = width * height
                    if (size <= 0 || size > MAX_MASK_BYTES) {
                        Log.e("XR_LAB", "Mask size too large: $size")
                        return@withContext
                    }
                    val data = ByteArray(size)
                    input.readFully(data)
                    _maskFlow.tryEmit(MaskFrame(width, height, data))
                } catch (_: SocketTimeoutException) {
                    continue
                } catch (e: EOFException) {
                    return@withContext
                } catch (e: Exception) {
                    Log.e("XR_LAB", "Mask receive error: ${e.message}")
                    close()
                    return@withContext
                }
            }
        }
    }

    fun close() {
        try {
            socket?.close()
            outputStream?.close()
            inputStream?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            socket = null
            outputStream = null
            inputStream = null
        }
    }

    companion object {
        private const val MAX_MASK_BYTES = 1920 * 1080
    }
}
