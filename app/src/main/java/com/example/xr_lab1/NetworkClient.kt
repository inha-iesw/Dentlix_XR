import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataOutputStream
import java.net.Socket
import java.nio.ByteBuffer

class NetworkClient {
    // ADB 포트 포워딩을 사용하므로 127.0.0.1 사용
    private val SERVER_IP = "127.0.0.1"
    private val SERVER_PORT = 5000

    private var socket: Socket? = null
    private var outputStream: DataOutputStream? = null

    // 연결 함수
    suspend fun connect() {
        withContext(Dispatchers.IO) {
            try {
                // 이미 연결되어 있으면 패스
                if (socket != null && !socket!!.isClosed && socket!!.isConnected) return@withContext

                Log.d("XR_LAB", "🔄 서버 연결 시도 중... ($SERVER_IP:$SERVER_PORT)")
                socket = Socket(SERVER_IP, SERVER_PORT)
                socket?.tcpNoDelay = true // 딜레이 없이 즉시 전송 옵션
                socket?.soTimeout = 5000  // 5초 동안 응답 없으면 끊기 (무한 대기 방지)
                outputStream = DataOutputStream(socket!!.getOutputStream())
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
                    // 이렇게 하면 TCP 패킷이 쪼개지는 확률을 줄임
                    val buffer = ByteBuffer.allocate(4 + size)
                    buffer.putInt(size)   // 길이 기록
                    buffer.put(jpegData)  // 이미지 기록

                    val combinedData = buffer.array()

                    // 한 번에 전송 (Write once)
                    stream.write(combinedData)
                    stream.flush() // 즉시 밀어내기

                    // Log.d("XR_LAB", "📤 프레임 전송 완료 (${combinedData.size} bytes)")
                }
            } catch (e: Exception) {
                // Broken pipe가 나면 여기서 잡힘
                Log.e("XR_LAB", "❌ 전송 중 에러 (Broken Pipe 등): ${e.message}")
                close() // 소켓을 닫아줘야 다음 프레임에서 깨끗하게 재연결함
            }
        }
    }

    fun close() {
        try {
            socket?.close()
            outputStream?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            socket = null
            outputStream = null
        }
    }
}