package com.example.xr_lab1

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.Surface as AndroidSurface
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
import androidx.compose.material3.Surface as ComposeSurface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.example.xr_lab1.ui.theme.XR_lab1Theme
import androidx.xr.runtime.Config
import androidx.xr.runtime.Session
import androidx.xr.runtime.SessionCreateApkRequired
import androidx.xr.runtime.SessionCreateSuccess
import androidx.xr.runtime.SessionCreateUnsupportedDevice
import androidx.xr.runtime.SessionConfigureSuccess
import androidx.xr.runtime.math.FloatSize2d
import androidx.xr.runtime.math.IntSize2d
import androidx.xr.runtime.math.Pose
import androidx.xr.runtime.math.Vector3
import androidx.xr.scenecore.SurfaceEntity
import androidx.xr.scenecore.Space
import androidx.xr.scenecore.scene
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private companion object {
        private val HUD_PANEL_SIZE = FloatSize2d(0.42f, 0.30f)
        private val HUD_OFFSET = Vector3(0.01f, 0.02f, -0.20f)
    }

    private val useWavKwsTest = false
    private val kwsTestWavAssetPath = "practice/Zoom_test1_answer_3.wav"

    private lateinit var cameraExecutor: ExecutorService
    private var xrSession: Session? = null
    private var surfaceEntity: SurfaceEntity? = null
    private var xrOverlaySurface: AndroidSurface? = null
    private var overlayRenderer: XrOverlayRenderer? = null
    private var kwsEngine: KwsEngine? = null
    private var headLockJob: Job? = null

    private val requestPermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            val cameraGranted = grants[Manifest.permission.CAMERA] == true
            val micGranted = grants[Manifest.permission.RECORD_AUDIO] == true
            if (cameraGranted) {
                startCamera()
            } else {
                Log.e("XR_LAB", "카메라 권한이 거부되었습니다.")
            }
            if (useWavKwsTest) {
                startKws()
            } else if (micGranted) {
                startKws()
            } else {
                Log.e("XR_KWS", "마이크 권한이 거부되었습니다.")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        cameraExecutor = Executors.newSingleThreadExecutor()
        try {
            kwsEngine = KwsEngine(
                context = this,
                modelAssetPath = "model/model_kws_v3_int8.tflite",
                labels = listOf("reset", "silence", "unknown", "zoom"),
                triggerLabels = setOf("zoom", "reset"),
                triggerThreshold = 0.6f,
                testWavAssetPath = if (useWavKwsTest) kwsTestWavAssetPath else null,
            ) { label, score ->
                runOnUiThread {
                    Log.i("XR_KWS", "Keyword detected: $label ($score)")
                    when (label) {
                        "zoom" -> overlayRenderer?.setZoom(6.0f)
                        "reset" -> overlayRenderer?.setZoom(1.0f)
                    }
                }
            }
        } catch (e: Exception) {
            kwsEngine = null
            Log.e("XR_KWS", "KWS init failed. Put model at assets/model/model_kws_v3_int8.tflite", e)
        }

        if (hasPermission(Manifest.permission.CAMERA)) {
            startCamera()
        }
        if (useWavKwsTest || hasPermission(Manifest.permission.RECORD_AUDIO)) {
            startKws()
        }
        val requiredPermissions = mutableListOf<String>()
        if (!hasPermission(Manifest.permission.CAMERA)) {
            requiredPermissions.add(Manifest.permission.CAMERA)
        }
        if (!useWavKwsTest && !hasPermission(Manifest.permission.RECORD_AUDIO)) {
            requiredPermissions.add(Manifest.permission.RECORD_AUDIO)
        }
        if (requiredPermissions.isNotEmpty()) {
            requestPermissionsLauncher.launch(requiredPermissions.toTypedArray())
        }

        setContent {
            XR_lab1Theme {
                ComposeSurface(
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
        kwsEngine?.close()
        headLockJob?.cancel()
        cameraExecutor.shutdown()
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun startKws() {
        try {
            kwsEngine?.start()
        } catch (e: Exception) {
            Log.e("XR_KWS", "KWS start failed", e)
        }
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
                        shape = SurfaceEntity.Shape.Quad(HUD_PANEL_SIZE),
                        stereoMode = SurfaceEntity.StereoMode.MONO,
                    )
                entity.parent = session.scene.activitySpace
                @Suppress("OPT_IN_USAGE", "OPT_IN_USAGE_ERROR")
                @SuppressLint("RestrictedApi")
                entity.setSurfacePixelDimensions(IntSize2d(640, 480))
                entity.edgeFeatheringParams =
                    SurfaceEntity.EdgeFeatheringParams.RectangleFeather(
                        leftRight = 0.00f,
                        topBottom = 0.00f
                    )
                entity.setAlpha(1.0f)
                Log.d("XR_LAB", "Surface entity created")
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
                        val offset =
                            headPose.right * HUD_OFFSET.x +
                                headPose.up * HUD_OFFSET.y +
                                headPose.forward * -HUD_OFFSET.z
                        val targetPose = headPose.translate(offset)
                        entity.setPose(targetPose, Space.ACTIVITY)
                    }
                    delay(16)
                }
            }
    }

    private fun startXrOverlayRenderer(surface: AndroidSurface) {
        if (overlayRenderer == null) {
            overlayRenderer = XrOverlayRenderer()
            overlayRenderer?.setZoom(4.0f)
            overlayRenderer?.setZoomCenterX(0.4f)
            overlayRenderer?.setZoomCenterY(1.0f)
            overlayRenderer?.setInsetSize(0.38f, 0.38f)
            overlayRenderer?.setInsetMargin(0.03f, 0.04f)
        }
        overlayRenderer?.start(surface)
        Log.d("XR_LAB", "XR overlay surface ready: $surface")
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
