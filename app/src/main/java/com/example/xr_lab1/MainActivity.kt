package com.example.xr_lab1

import NetworkClient
import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.xr.compose.platform.LocalSession
import androidx.xr.compose.platform.LocalSpatialCapabilities
import androidx.xr.compose.platform.LocalSpatialConfiguration
import androidx.xr.compose.spatial.ContentEdge
import androidx.xr.compose.spatial.Orbiter
import androidx.xr.compose.spatial.Subspace
import androidx.xr.compose.subspace.SpatialPanel
import androidx.xr.compose.subspace.layout.SpatialRoundedCornerShape
import androidx.xr.compose.subspace.layout.SubspaceModifier
import androidx.xr.compose.subspace.layout.height
import androidx.xr.compose.subspace.layout.movable
import androidx.xr.compose.subspace.layout.resizable
import androidx.xr.compose.subspace.layout.width
import com.example.xr_lab1.ui.theme.XR_lab1Theme
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    // 카메라 처리를 위한 백그라운드 쓰레드
    private lateinit var cameraExecutor: ExecutorService

    // 권한 요청 런처
    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                Log.d("XR_LAB", "권한 허용됨. 카메라 시작.")
                startCamera()
            } else {
                Log.e("XR_LAB", "카메라 권한이 거부되었습니다.")
            }
        }

    private val networkClient = NetworkClient()
    private val scope = CoroutineScope(Dispatchers.IO)

    @Volatile
    private var isSending = false

    @SuppressLint("RestrictedApi")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

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

        // UI 코드
        setContent {
            XR_lab1Theme {
                val spatialConfiguration = LocalSpatialConfiguration.current
                if (LocalSpatialCapabilities.current.isSpatialUiEnabled) {
                    Subspace {
                        MySpatialContent(
                            onRequestHomeSpaceMode = spatialConfiguration::requestHomeSpaceMode
                        )
                    }
                } else {
                    My2DContent(onRequestFullSpaceMode = spatialConfiguration::requestFullSpaceMode)
                }
            }
        }

        scope.launch {
            networkClient.connect()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // 앱 꺼지면 스레드, 소켓 종료
        cameraExecutor.shutdown()
        networkClient.close()
    }

    // 카메라 관련 함수 startCamera, processImage
    private fun startCamera(){
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            try{
                val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

                // 분석용 파이프라인 설정
                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()

                // 이미지 분석 로직 연결
                imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                    processImage(imageProxy)
                }

                // XR 기기 카메라 인식 (기본 후면 카메라)
                val cameraSelector = if (cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) {
                    Log.d("XR_LAB", "후면 카메라를 찾았습니다.")
                    CameraSelector.DEFAULT_BACK_CAMERA
                } else if (cameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) {
                    Log.d("XR_LAB", "후면 카메라가 없어 전면 카메라를 사용합니다.")
                    CameraSelector.DEFAULT_FRONT_CAMERA
                } else {
                    throw IllegalStateException("사용 가능한 카메라가 없습니다.")
                }

                cameraProvider.unbindAll()

                // Preview 없이 Analysis만 바인딩함 (화면엔 안 보이고 데이터만)
                cameraProvider.bindToLifecycle(
                    this,
                    cameraSelector,
                    imageAnalysis
                )
                Log.d("XR_LAB", "카메라 바인딩 성공")

            }catch (exc: Exception){
                Log.e("XR_LAB", "카메라 바인딩 실패", exc)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun processImage(imageProxy: ImageProxy) {
        if (isSending) {
            imageProxy.close()
            return
        }

        try {
//            YUV 데이터 추출
//            val buffer = imageProxy.planes[0].buffer
//            val data = ByteArray(buffer.remaining())
//            buffer.get(data)

            // XR 디바이스에서 JPEG로 압축해서 서버로 전송하기
            // toBitmap() 후 압축

            isSending = true

            val bitmap = imageProxy.toBitmap() //  YUV -> ARGB 변환

            val stream = ByteArrayOutputStream()
            // 퀄리티 80%로 압축 (속도/화질 타협)
            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, stream)
            val jpegByteArray = stream.toByteArray()

            // 서버로 전송
            scope.launch {
                try {
                    // 3. 전송
                    networkClient.sendFrame(jpegByteArray)
                    kotlinx.coroutines.delay(30)
                } catch (e: Exception) {
                    Log.e("XR_LAB", "전송 중 에러: ${e.message}")
                } finally {
                    // 4. 전송이 끝나면(성공하든 실패하든) 깃발 내리기
                    isSending = false
                }
            }

        } catch (e: Exception) {
            Log.e("XR_LAB", "Image Analysis Error", e)
        } finally {
            // 다음 프레임이 들어오기 위해 close
            imageProxy.close()
        }
    }
}

// XR 템플릿 UI 코드
@SuppressLint("RestrictedApi")
@Composable
fun MySpatialContent(onRequestHomeSpaceMode: () -> Unit) {
    SpatialPanel(SubspaceModifier.width(1280.dp).height(800.dp).resizable().movable()) {
        Surface {
            MainContent(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(48.dp)
            )
        }
        Orbiter(
            position = ContentEdge.Top,
            offset = 20.dp,
            alignment = Alignment.End,
            shape = SpatialRoundedCornerShape(CornerSize(28.dp))
        ) {
            HomeSpaceModeIconButton(
                onClick = onRequestHomeSpaceMode,
                modifier = Modifier.size(56.dp)
            )
        }
    }
}

@SuppressLint("RestrictedApi")
@Composable
fun My2DContent(onRequestFullSpaceMode: () -> Unit) {
    Surface {
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            MainContent(modifier = Modifier.padding(48.dp))
            // Preview does not current support XR sessions.
            if (!LocalInspectionMode.current && LocalSession.current != null) {
                FullSpaceModeIconButton(
                    onClick = onRequestFullSpaceMode,
                    modifier = Modifier.padding(32.dp)
                )
            }
        }
    }
}

@Composable
fun MainContent(modifier: Modifier = Modifier) {
    Text(text = stringResource(R.string.XR_camera_tracking), modifier = modifier)
}

@Composable
fun FullSpaceModeIconButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(onClick = onClick, modifier = modifier) {
        Icon(
            painter = painterResource(id = R.drawable.ic_full_space_mode_switch),
            contentDescription = stringResource(R.string.switch_to_full_space_mode)
        )
    }
}

@Composable
fun HomeSpaceModeIconButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    FilledTonalIconButton(onClick = onClick, modifier = modifier) {
        Icon(
            painter = painterResource(id = R.drawable.ic_home_space_mode_switch),
            contentDescription = stringResource(R.string.switch_to_home_space_mode)
        )
    }
}

@PreviewLightDark
@Composable
fun My2dContentPreview() {
    XR_lab1Theme {
        My2DContent(onRequestFullSpaceMode = {})
    }
}

@Preview(showBackground = true)
@Composable
fun FullSpaceModeButtonPreview() {
    XR_lab1Theme {
        FullSpaceModeIconButton(onClick = {})
    }
}

@PreviewLightDark
@Composable
fun HomeSpaceModeButtonPreview() {
    XR_lab1Theme {
        HomeSpaceModeIconButton(onClick = {})
    }
}