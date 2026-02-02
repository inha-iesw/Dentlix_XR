package com.example.xr_lab1

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.util.Log
import android.view.Surface
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.example.xr_lab1.ui.theme.XR_lab1Theme
import androidx.xr.runtime.Session
import androidx.xr.runtime.Config
import androidx.xr.runtime.SessionCreateApkRequired
import androidx.xr.runtime.SessionCreateSuccess
import androidx.xr.runtime.SessionCreateUnsupportedDevice
import androidx.xr.runtime.SessionConfigureSuccess
import androidx.xr.runtime.math.FloatSize2d
import androidx.xr.runtime.math.IntSize2d
import androidx.xr.runtime.math.Pose
import androidx.xr.scenecore.SurfaceEntity
import androidx.xr.scenecore.Space
import androidx.xr.scenecore.scene
import com.whispercpp.whisper.WhisperContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.runBlocking
import kotlin.math.max
import kotlin.math.min

class MainActivity : ComponentActivity() {

    private lateinit var cameraExecutor: ExecutorService
    private var xrSession: Session? = null
    private var surfaceEntity: SurfaceEntity? = null
    private var xrOverlaySurface: Surface? = null
    private var overlayRenderer: XrOverlayRenderer? = null
    private var headLockJob: Job? = null
    private var whisperContext: WhisperContext? = null
    private var audioJob: Job? = null
    private var audioRecord: AudioRecord? = null
    private var currentZoom = 1.0f

    private val whisperSampleRate = 16000
    private val defaultZoom = 4.0f
    private val minZoom = 1.0f
    private val vadThreshold = 800
    private val vadSilenceMs = 1000
    private val vadMinSpeechMs = 500

    // 권한 요청 런처
    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                startCamera()
            } else {
                Log.e("XR_LAB", "카메라 권한이 거부되었습니다.")
            }
        }

    private val requestAudioPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                startWhisperListening()
            } else {
                Log.e("XR_LAB_WHISPER", "오디오 권한이 거부되었습니다.")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d("XR_LAB", "onCreate: enter")

        // 카메라 쓰레드 초기화
        cameraExecutor = Executors.newSingleThreadExecutor()

        // 권한 체크 및 카메라 시작
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startWhisperListening()
        } else {
            requestAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }

        setContent {
            XR_lab1Theme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    SimpleScreen()
                }
            }
        }

        setupXrScene()
    }

    override fun onDestroy() {
        super.onDestroy()
        surfaceEntity?.dispose()
        xrOverlaySurface?.release()
        overlayRenderer?.stop()
        headLockJob?.cancel()
        audioJob?.cancel()
        audioRecord?.let { record ->
            try {
                record.stop()
            } catch (e: IllegalStateException) {
                Log.w("XR_LAB_WHISPER", "AudioRecord stop failed", e)
            }
            record.release()
        }
        runBlocking {
            whisperContext?.release()
        }
        cameraExecutor.shutdown()
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()
                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()

                imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                    processImage(imageProxy)
                }

                val cameraSelector = if (cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) {
                    Log.d("XR_LAB", "후면 카메라를 찾았습니다.")
                    CameraSelector.DEFAULT_BACK_CAMERA
                } else if (cameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) {
                    Log.d("XR_LAB", "후면 카메라가 없어 전면 카메라를 사용합니다.")
                    CameraSelector.DEFAULT_FRONT_CAMERA
                } else {
                    Log.e("XR_LAB", "사용 가능한 카메라가 없습니다.")
                    return@addListener
                }

                cameraProvider.unbindAll()

                cameraProvider.bindToLifecycle(
                    this,
                    cameraSelector,
                    imageAnalysis
                )
                Log.d("XR_LAB", "카메라 바인딩 성공")

            } catch (exc: Exception) {
                Log.e("XR_LAB", "카메라 바인딩 실패", exc)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun processImage(imageProxy: ImageProxy) {
        try {
            val bitmap = imageProxy.toBitmap()
            overlayRenderer?.updateCameraFrame(bitmap)
        } catch (e: Exception) {
            Log.e("XR_LAB", "Image Analysis Error", e)
        } finally {
            imageProxy.close()
        }
    }

    private fun setupXrScene() {
        if (xrSession != null) return

        when (val result = Session.create(this)) {
            is SessionCreateSuccess -> {
                val session = result.session
                xrSession = session
                session.scene.requestFullSpaceMode()
                session.scene.mainPanelEntity.setEnabled(false)

                val configResult =
                    session.configure(
                        session.config.copy(headTracking = Config.HeadTrackingMode.LAST_KNOWN)
                    )
                if (configResult !is SessionConfigureSuccess) {
                    Log.e("XR_LAB", "Session configure failed: $configResult")
                }

                val entity =
                    SurfaceEntity.create(
                        session = session,
                        pose = Pose.Identity,
                        shape = SurfaceEntity.Shape.Quad(FloatSize2d(0.6f, 0.5f)),
                        stereoMode = SurfaceEntity.StereoMode.MONO,
                    )
                entity.parent = session.scene.activitySpace
                @SuppressLint("RestrictedApi")
                entity.setSurfacePixelDimensions(IntSize2d(640, 480))
                entity.edgeFeatheringParams =
                    SurfaceEntity.EdgeFeatheringParams.RectangleFeather(
                        leftRight = 0.00f,
                        topBottom = 0.00f
                    )
                entity.setAlpha(1.0f)
                Log.d("XR_LAB", "Surface pixel size set: 640x480")
                surfaceEntity = entity

                val surface = entity.getSurface()
                xrOverlaySurface = surface
                startXrOverlayRenderer(surface)
                startHeadLockedUpdates(session, entity)
            }
            is SessionCreateApkRequired -> {
                Log.e("XR_LAB", "XR Session requires APK: ${result.requiredApk}")
            }
            is SessionCreateUnsupportedDevice -> {
                Log.e("XR_LAB", "XR Session unsupported on this device")
            }
        }
    }

    private fun startHeadLockedUpdates(session: Session, entity: SurfaceEntity) {
        headLockJob?.cancel()
        headLockJob =
            lifecycleScope.launch {
                while (isActive) {
                    val head = session.scene.spatialUser.head
                    if (head != null) {
                        val headPose = head.activitySpacePose
                        val offset = headPose.forward * 0.4f
                        val targetPose = headPose.translate(offset)
                        entity.setPose(targetPose, Space.ACTIVITY)
                    }
                    delay(16)
                }
            }
    }

    private fun startXrOverlayRenderer(surface: Surface) {
        if (overlayRenderer == null) {
            overlayRenderer = XrOverlayRenderer()
            overlayRenderer?.setZoom(1.0f)
            overlayRenderer?.setZoomCenterX(0.4f)
            overlayRenderer?.setZoomCenterY(1.0f)
            overlayRenderer?.setInsetSize(0.38f, 0.38f)
            overlayRenderer?.setInsetMargin(0.04f, 0.04f)
        }
        currentZoom = 1.0f
        overlayRenderer?.start(surface)
        Log.d("XR_LAB", "XR overlay surface ready: $surface")
    }

    private fun startWhisperListening() {
        audioJob?.cancel()
        audioJob = lifecycleScope.launch(Dispatchers.IO) {
            Log.d("XR_LAB_WHISPER", "startWhisperListening: begin")
            try {
                val context = WhisperContext.createContextFromAsset(assets, "model/ggml-tiny.bin")
                whisperContext = context
                Log.d("XR_LAB_WHISPER", "startWhisperListening: model loaded")

                val minBufferBytes = AudioRecord.getMinBufferSize(
                    whisperSampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                if (minBufferBytes <= 0) {
                    throw IllegalStateException("Invalid AudioRecord buffer size: $minBufferBytes")
                }
                val minBufferShorts = max(1, minBufferBytes / 2)
                val record = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    whisperSampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    max(minBufferBytes, whisperSampleRate * 2)
                )
                audioRecord = record
                record.startRecording()
                Log.d("XR_LAB_WHISPER", "startWhisperListening: recording started")

                val readBuffer = ShortArray(minBufferShorts)
                val maxSpeechSamples = whisperSampleRate * 10
                var speechBuffer = ShortArray(maxSpeechSamples)
                var speechLen = 0
                var inSpeech = false
                var silenceSamples = 0
                val vadSilenceSamples = (whisperSampleRate * vadSilenceMs) / 1000
                val vadMinSpeechSamples = (whisperSampleRate * vadMinSpeechMs) / 1000

                while (isActive) {
                    val read = record.read(readBuffer, 0, readBuffer.size)
                    if (read <= 0) continue
                    var sum = 0L
                    for (i in 0 until read) {
                        sum += kotlin.math.abs(readBuffer[i].toInt())
                    }
                    val avgAbs = (sum / read).toInt()
                    val isSpeech = avgAbs > vadThreshold

                    if (isSpeech) {
                        if (!inSpeech) {
                            inSpeech = true
                            speechLen = 0
                            silenceSamples = 0
                            Log.d("XR_LAB_WHISPER", "Speech start detected")
                        }
                        silenceSamples = 0
                    } else if (inSpeech) {
                        silenceSamples += read
                    }

                    if (inSpeech) {
                        val needed = speechLen + read
                        if (needed > speechBuffer.size) {
                            val newSize = max(needed, speechBuffer.size * 2)
                            speechBuffer = speechBuffer.copyOf(newSize)
                        }
                        System.arraycopy(readBuffer, 0, speechBuffer, speechLen, read)
                        speechLen += read

                        if (silenceSamples >= vadSilenceSamples) {
                            val endLen = max(0, speechLen - silenceSamples)
                            if (endLen >= vadMinSpeechSamples) {
                                Log.d("XR_LAB_WHISPER", "Speech end detected (samples=$endLen)")
                                Log.d("XR_LAB_WHISPER", "STT start")
                                val audio = FloatArray(endLen)
                                for (i in 0 until endLen) {
                                    audio[i] = speechBuffer[i] / 32768.0f
                                }
                                val result = context.transcribeData(audio, printTimestamp = false)
                                Log.d("XR_LAB_WHISPER", "STT end")
                                handleWhisperCommand(result)
                            } else {
                                Log.d("XR_LAB_WHISPER", "VAD discard short speech samples=$endLen")
                            }
                            inSpeech = false
                            silenceSamples = 0
                            speechLen = 0
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("XR_LAB_WHISPER", "Whisper listening failed", e)
            }
            Log.d("XR_LAB_WHISPER", "startWhisperListening: end")
        }
    }

    private fun handleWhisperCommand(text: String) {
        val normalized = text.trim().replace(Regex("\\s+"), " ")
        Log.i("XR_LAB_WHISPER", "STT result: $normalized")
        val targetZoom = parseZoomCommand(normalized) ?: return
        lifecycleScope.launch(Dispatchers.Main) {
            applyZoom(targetZoom)
        }
    }

    private fun parseZoomCommand(text: String): Float? {
        if (text.contains("초기화")) {
            return minZoom
        }
        if (!text.contains("확대")) {
            return null
        }
        val match = Regex("(\\d+)\\s*배").find(text)
        if (match != null) {
            val value = match.groupValues[1].toFloatOrNull()
            if (value != null && value > 0f) {
                return value
            }
        }
        val koreanNumber = mapOf(
            "한" to 1f,
            "두" to 2f,
            "세" to 3f,
            "새" to 3f,
            "네" to 4f,
            "내" to 4f,
            "다섯" to 5f
        )
        for ((key, value) in koreanNumber) {
            if (text.contains("$key 배")) {
                return value
            }
        }
        return defaultZoom
    }

    private fun applyZoom(zoom: Float) {
        val nextZoom = max(minZoom, zoom)
        if (currentZoom == nextZoom) return
        currentZoom = nextZoom
        overlayRenderer?.setZoom(nextZoom)
        Log.d("XR_LAB_WHISPER", "Zoom set to $nextZoom")
    }
}

@Composable
fun SimpleScreen(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(text = "카메라 데이터 전송 중...")
    }
}
