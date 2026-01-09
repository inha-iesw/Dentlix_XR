package com.example.xr_lab1

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
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
import androidx.xr.runtime.math.Vector3
import androidx.xr.scenecore.SurfaceEntity
import androidx.xr.scenecore.Space
import androidx.xr.scenecore.scene
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import androidx.lifecycle.lifecycleScope

class MainActivity : ComponentActivity() {

    private lateinit var cameraExecutor: ExecutorService
    private var xrSession: Session? = null
    private var surfaceEntity: SurfaceEntity? = null
    private var xrOverlaySurface: Surface? = null
    private var overlayRenderer: XrOverlayRenderer? = null
    private var headLockJob: Job? = null

    // 권한 요청 런처
    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                startCamera()
            } else {
                Log.e("XR_LAB", "카메라 권한이 거부되었습니다.")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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
            overlayRenderer?.setZoomCenterY(0.95f)
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
