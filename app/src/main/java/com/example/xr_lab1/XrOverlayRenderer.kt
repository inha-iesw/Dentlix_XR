package com.example.xr_lab1

import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.util.Log
import android.view.Surface
import android.os.SystemClock
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

class XrOverlayRenderer {
    private val lock = Object()
    private var renderThread: Thread? = null
    @Volatile
    private var running = false

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    private var program = 0
    private var vao = 0
    private var cameraTex = 0
    private var uZoomLocation = 0
    private var uZoomCenterXLocation = 0
    private var uZoomCenterYLocation = 0
    private var uInsetSizeLocation = 0
    private var uInsetMarginLocation = 0

    private var surfaceWidth = 1
    private var surfaceHeight = 1

    private var cameraBuffer: ByteBuffer? = null
    private var cameraWidth = 0
    private var cameraHeight = 0
    private var cameraDirty = false

    private var lastCameraLogMs = 0L
    private var lastRenderLogMs = 0L
    @Volatile
    private var zoom = 3.0f
    @Volatile
    private var zoomCenterX = 0.5f
    @Volatile
    private var zoomCenterY = 0.5f
    @Volatile
    private var insetWidth = 0.45f
    @Volatile
    private var insetHeight = 0.45f
    @Volatile
    private var insetMarginX = 0.04f
    @Volatile
    private var insetMarginY = 0.04f

    fun setZoom(scale: Float) {
        zoom = scale.coerceAtLeast(1.0f)
    }

    fun setZoomCenterY(centerY: Float) {
        zoomCenterY = centerY.coerceIn(0.0f, 1.0f)
    }

    fun setZoomCenterX(centerX: Float) {
        zoomCenterX = centerX.coerceIn(0.0f, 1.0f)
    }

    fun setInsetSize(width: Float, height: Float) {
        insetWidth = width.coerceIn(0.05f, 1.0f)
        insetHeight = height.coerceIn(0.05f, 1.0f)
    }

    fun setInsetMargin(marginX: Float, marginY: Float) {
        insetMarginX = marginX.coerceIn(0.0f, 0.45f)
        insetMarginY = marginY.coerceIn(0.0f, 0.45f)
    }

    fun start(surface: Surface) {
        if (running) return
        running = true
        renderThread = Thread {
            try {
                initEgl(surface)
                initGlObjects()
                renderLoop()
            } catch (e: Exception) {
                Log.e("XR_LAB", "Renderer error: ${e.message}", e)
            } finally {
                releaseGlObjects()
                releaseEgl()
            }
        }.apply { name = "XrOverlayRenderer" }
        renderThread?.start()
    }

    fun stop() {
        running = false
        synchronized(lock) {
            lock.notifyAll()
        }
        renderThread?.join(1000)
        renderThread = null
    }

    fun updateCameraFrame(bitmap: Bitmap) {
        val width = bitmap.width
        val height = bitmap.height
        val buffer = ByteBuffer.allocateDirect(width * height * 4)
        buffer.order(ByteOrder.nativeOrder())
        bitmap.copyPixelsToBuffer(buffer)
        buffer.rewind()
        val now = SystemClock.elapsedRealtime()
        if (now - lastCameraLogMs > 1000) {
            val p0 = buffer.get(0).toInt() and 0xFF
            val p1 = buffer.get(1).toInt() and 0xFF
            val p2 = buffer.get(2).toInt() and 0xFF
            val p3 = buffer.get(3).toInt() and 0xFF
            Log.d("XR_LAB", "Camera frame: ${width}x${height} p0=[$p0,$p1,$p2,$p3]")
            lastCameraLogMs = now
        }
        synchronized(lock) {
            cameraBuffer = buffer
            cameraWidth = width
            cameraHeight = height
            cameraDirty = true
            lock.notifyAll()
        }
    }

    private fun initEgl(surface: Surface) {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        EGL14.eglInitialize(eglDisplay, version, 0, version, 1)

        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, 0x40,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        EGL14.eglChooseConfig(eglDisplay, attribs, 0, configs, 0, configs.size, numConfigs, 0)
        val config = configs[0] ?: throw IllegalStateException("No EGL config")

        val ctxAttribs = intArrayOf(
            EGL14.EGL_CONTEXT_CLIENT_VERSION, 3,
            EGL14.EGL_NONE
        )
        eglContext =
            EGL14.eglCreateContext(eglDisplay, config, EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)

        val width = IntArray(1)
        val height = IntArray(1)
        EGL14.eglQuerySurface(eglDisplay, eglSurface, EGL14.EGL_WIDTH, width, 0)
        EGL14.eglQuerySurface(eglDisplay, eglSurface, EGL14.EGL_HEIGHT, height, 0)
        surfaceWidth = width[0].coerceAtLeast(1)
        surfaceHeight = height[0].coerceAtLeast(1)
        Log.d("XR_LAB", "EGL surface size: ${surfaceWidth}x${surfaceHeight}")
    }

    private fun initGlObjects() {
        program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        GLES30.glUseProgram(program)

        val vaoIds = IntArray(1)
        GLES30.glGenVertexArrays(1, vaoIds, 0)
        vao = vaoIds[0]
        GLES30.glBindVertexArray(vao)
        GLES30.glBindVertexArray(0)

        cameraTex = createTexture2d()
        setTextureImage(cameraTex, 1, 1, ByteBuffer.allocateDirect(4))

        val uCamera = GLES30.glGetUniformLocation(program, "uCamera")
        uZoomLocation = GLES30.glGetUniformLocation(program, "uZoom")
        uZoomCenterXLocation = GLES30.glGetUniformLocation(program, "uZoomCenterX")
        uZoomCenterYLocation = GLES30.glGetUniformLocation(program, "uZoomCenterY")
        uInsetSizeLocation = GLES30.glGetUniformLocation(program, "uInsetSize")
        uInsetMarginLocation = GLES30.glGetUniformLocation(program, "uInsetMargin")
        GLES30.glUniform1i(uCamera, 0)
        GLES30.glUniform1f(uZoomLocation, zoom)
        GLES30.glUniform1f(uZoomCenterXLocation, zoomCenterX)
        GLES30.glUniform1f(uZoomCenterYLocation, zoomCenterY)
        GLES30.glUniform2f(uInsetSizeLocation, insetWidth, insetHeight)
        GLES30.glUniform2f(uInsetMarginLocation, insetMarginX, insetMarginY)

        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
    }

    private fun renderLoop() {
        while (running) {
            val frameStartMs = SystemClock.elapsedRealtime()
            var localCamera: ByteBuffer? = null
            val camW: Int
            val camH: Int

            synchronized(lock) {
                if (!cameraDirty) {
                    try {
                        lock.wait(33)
                    } catch (_: InterruptedException) {
                    }
                }
                localCamera = cameraBuffer
                camW = cameraWidth
                camH = cameraHeight
                cameraDirty = false
            }

            if (localCamera != null && camW > 0 && camH > 0) {
                GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, cameraTex)
                uploadTexture(cameraTex, camW, camH, localCamera)
            }

            GLES30.glUseProgram(program)
            GLES30.glUniform1f(uZoomLocation, zoom)
            GLES30.glUniform1f(uZoomCenterXLocation, zoomCenterX)
            GLES30.glUniform1f(uZoomCenterYLocation, zoomCenterY)
            GLES30.glUniform2f(uInsetSizeLocation, insetWidth, insetHeight)
            GLES30.glUniform2f(uInsetMarginLocation, insetMarginX, insetMarginY)
            GLES30.glViewport(0, 0, surfaceWidth, surfaceHeight)
            GLES30.glClearColor(0f, 0f, 0f, 0f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            GLES30.glBindVertexArray(vao)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
            GLES30.glBindVertexArray(0)
            EGL14.eglSwapBuffers(eglDisplay, eglSurface)
            // val frameEndMs = SystemClock.elapsedRealtime()
            // if (frameEndMs - lastRenderLogMs > 1000) {
            //     val renderMs = frameEndMs - frameStartMs
            //     Log.d("XR_LAB", "Render time: ${renderMs}ms")
            //     lastRenderLogMs = frameEndMs
            // }
        }
    }

    private fun uploadTexture(
        texId: Int,
        width: Int,
        height: Int,
        buffer: ByteBuffer
    ) {
        setTextureImage(texId, width, height, buffer)
    }

    private fun createTexture2d(): Int {
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, ids[0])
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        return ids[0]
    }

    private fun setTextureImage(texId: Int, width: Int, height: Int, buffer: ByteBuffer) {
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texId)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D,
            0,
            GLES30.GL_RGBA,
            width,
            height,
            0,
            GLES30.GL_RGBA,
            GLES30.GL_UNSIGNED_BYTE,
            buffer
        )
    }

    private fun createProgram(vertexSrc: String, fragSrc: String): Int {
        val vShader = compileShader(GLES30.GL_VERTEX_SHADER, vertexSrc)
        val fShader = compileShader(GLES30.GL_FRAGMENT_SHADER, fragSrc)
        val program = GLES30.glCreateProgram()
        GLES30.glAttachShader(program, vShader)
        GLES30.glAttachShader(program, fShader)
        GLES30.glLinkProgram(program)
        val link = IntArray(1)
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, link, 0)
        if (link[0] == 0) {
            val msg = GLES30.glGetProgramInfoLog(program)
            GLES30.glDeleteProgram(program)
            throw IllegalStateException("GL program link failed: $msg")
        }
        GLES30.glDeleteShader(vShader)
        GLES30.glDeleteShader(fShader)
        return program
    }

    private fun compileShader(type: Int, src: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, src)
        GLES30.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            val msg = GLES30.glGetShaderInfoLog(shader)
            GLES30.glDeleteShader(shader)
            throw IllegalStateException("GL shader compile failed: $msg")
        }
        return shader
    }

    private fun toFloatBuffer(data: FloatArray): FloatBuffer {
        val buffer = ByteBuffer.allocateDirect(data.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        buffer.put(data).position(0)
        return buffer
    }

    private fun releaseGlObjects() {
        if (program != 0) GLES30.glDeleteProgram(program)
        if (vao != 0) GLES30.glDeleteVertexArrays(1, intArrayOf(vao), 0)
        if (cameraTex != 0) GLES30.glDeleteTextures(1, intArrayOf(cameraTex), 0)
        program = 0
        vao = 0
        cameraTex = 0
    }

    private fun releaseEgl() {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(
                eglDisplay,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT
            )
            if (eglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(eglDisplay, eglSurface)
            }
            if (eglContext != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(eglDisplay, eglContext)
            }
            EGL14.eglTerminate(eglDisplay)
        }
        eglDisplay = EGL14.EGL_NO_DISPLAY
        eglContext = EGL14.EGL_NO_CONTEXT
        eglSurface = EGL14.EGL_NO_SURFACE
    }

    companion object {
        private const val VERTEX_SHADER = """
            #version 300 es
            out vec2 vUv;
            void main() {
                vec2 pos = vec2(
                    (gl_VertexID == 1) ? 3.0 : -1.0,
                    (gl_VertexID == 2) ? 3.0 : -1.0
                );
                vUv = 0.5 * (pos + vec2(1.0));
                gl_Position = vec4(pos, 0.0, 1.0);
            }
        """

        private const val FRAGMENT_SHADER = """
            #version 300 es
            precision mediump float;
            in vec2 vUv;
            uniform sampler2D uCamera;
            uniform float uZoom;
            uniform float uZoomCenterX;
            uniform float uZoomCenterY;
            uniform vec2 uInsetSize;
            uniform vec2 uInsetMargin;
            out vec4 fragColor;
            void main() {
                vec2 insetMin = vec2(1.0 - uInsetMargin.x - uInsetSize.x, uInsetMargin.y);
                vec2 insetMax = insetMin + uInsetSize;
                if (vUv.x < insetMin.x || vUv.x > insetMax.x ||
                    vUv.y < insetMin.y || vUv.y > insetMax.y) {
                    fragColor = vec4(0.0, 0.0, 0.0, 0.0);
                    return;
                }

                vec2 local = (vUv - insetMin) / uInsetSize;
                vec2 uv = vec2(local.x, 1.0 - local.y);
                vec2 zoomCenter = vec2(uZoomCenterX, uZoomCenterY);
                uv = (uv - zoomCenter) / uZoom + zoomCenter;
                uv = clamp(uv, 0.0, 1.0);
                vec3 cam = texture(uCamera, uv).rgb;
                fragColor = vec4(cam, 1.0);
            }
        """
    }
}
