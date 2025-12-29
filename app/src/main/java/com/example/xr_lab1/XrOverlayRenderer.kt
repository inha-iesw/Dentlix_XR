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
    private var maskTex = 0

    private var surfaceWidth = 1
    private var surfaceHeight = 1

    private var cameraBuffer: ByteBuffer? = null
    private var cameraWidth = 0
    private var cameraHeight = 0
    private var cameraDirty = false

    private var maskBuffer: ByteBuffer? = null
    private var maskWidth = 0
    private var maskHeight = 0
    private var maskDirty = false
    private var lastCameraLogMs = 0L
    private var lastMaskLogMs = 0L
    @Volatile
    private var debugMode = DebugMode.COMPOSITE

    enum class DebugMode(val id: Int) {
        COMPOSITE(0),
        CAMERA_ONLY(1),
        MASK_ONLY(2),
        SOLID_COLOR(3),
        UV_GRADIENT(4)
    }

    fun setDebugMode(mode: DebugMode) {
        debugMode = mode
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

    fun updateMask(bytes: ByteArray, width: Int, height: Int) {
        val ones = bytes.count { it.toInt() and 0xFF > 0 }
        val total = width * height
        if (total > 0) {
            val ratio = ones.toFloat() / total.toFloat()
            Log.d("XR_LAB", "Mask stats: ones=$ones total=$total ratio=$ratio")
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastMaskLogMs > 1000 && bytes.isNotEmpty()) {
            val first = bytes[0].toInt() and 0xFF
            Log.d("XR_LAB", "Mask first byte: $first (${width}x${height})")
            lastMaskLogMs = now
        }
        val buffer = ByteBuffer.allocateDirect(width * height)
        buffer.order(ByteOrder.nativeOrder())
        buffer.put(bytes)
        buffer.rewind()
        synchronized(lock) {
            maskBuffer = buffer
            maskWidth = width
            maskHeight = height
            maskDirty = true
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
        maskTex = createTexture2d(redOnly = true)

        setTextureImage(cameraTex, 1, 1, ByteBuffer.allocateDirect(4))
        setRedTextureImage(maskTex, 1, 1, ByteBuffer.allocateDirect(1))

        val uCamera = GLES30.glGetUniformLocation(program, "uCamera")
        val uMask = GLES30.glGetUniformLocation(program, "uMask")
        val uOverlayColor = GLES30.glGetUniformLocation(program, "uOverlayColor")
        val uOverlayAlpha = GLES30.glGetUniformLocation(program, "uOverlayAlpha")
        val uDebugMode = GLES30.glGetUniformLocation(program, "uDebugMode")
        GLES30.glUniform1i(uCamera, 0)
        GLES30.glUniform1i(uMask, 1)
        GLES30.glUniform3f(uOverlayColor, 0f, 1f, 0f)
        GLES30.glUniform1f(uOverlayAlpha, 0.8f)
        GLES30.glUniform1i(uDebugMode, debugMode.id)

        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
    }

    private fun renderLoop() {
        while (running) {
            var localCamera: ByteBuffer? = null
            var localMask: ByteBuffer? = null
            val camW: Int
            val camH: Int
            val maskW: Int
            val maskH: Int

            synchronized(lock) {
                if (!cameraDirty && !maskDirty) {
                    try {
                        lock.wait(33)
                    } catch (_: InterruptedException) {
                    }
                }
                localCamera = cameraBuffer
                localMask = maskBuffer
                camW = cameraWidth
                camH = cameraHeight
                maskW = maskWidth
                maskH = maskHeight
                cameraDirty = false
                maskDirty = false
            }

            if (localCamera != null && camW > 0 && camH > 0) {
                GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, cameraTex)
                uploadTexture(cameraTex, camW, camH, localCamera, redOnly = false)
            }
            if (localMask != null && maskW > 0 && maskH > 0) {
                GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, maskTex)
                uploadTexture(maskTex, maskW, maskH, localMask, redOnly = true)
            }

            GLES30.glUseProgram(program)
            val uDebugMode = GLES30.glGetUniformLocation(program, "uDebugMode")
            GLES30.glUniform1i(uDebugMode, debugMode.id)
            GLES30.glViewport(0, 0, surfaceWidth, surfaceHeight)
            GLES30.glClearColor(0f, 0f, 0f, 0f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            GLES30.glBindVertexArray(vao)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
            GLES30.glBindVertexArray(0)
            EGL14.eglSwapBuffers(eglDisplay, eglSurface)
        }
    }

    private fun uploadTexture(
        texId: Int,
        width: Int,
        height: Int,
        buffer: ByteBuffer,
        redOnly: Boolean
    ) {
        if (redOnly) {
            setRedTextureImage(texId, width, height, buffer)
        } else {
            setTextureImage(texId, width, height, buffer)
        }
    }

    private fun createTexture2d(redOnly: Boolean = false): Int {
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

    private fun setRedTextureImage(texId: Int, width: Int, height: Int, buffer: ByteBuffer) {
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texId)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D,
            0,
            GLES30.GL_R8,
            width,
            height,
            0,
            GLES30.GL_RED,
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
        if (maskTex != 0) GLES30.glDeleteTextures(1, intArrayOf(maskTex), 0)
        program = 0
        vao = 0
        cameraTex = 0
        maskTex = 0
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
            uniform sampler2D uMask;
            uniform vec3 uOverlayColor;
            uniform float uOverlayAlpha;
            uniform int uDebugMode;
            out vec4 fragColor;
            void main() {
                vec2 uv = vec2(vUv.x, 1.0 - vUv.y);
                vec3 cam = texture(uCamera, uv).rgb;
                float mask = texture(uMask, uv).r;
                if (uDebugMode == 1) {
                    fragColor = vec4(cam, 1.0);
                    return;
                }
                if (uDebugMode == 2) {
                    fragColor = vec4(vec3(mask), 1.0);
                    return;
                }
                if (uDebugMode == 3) {
                    fragColor = vec4(1.0, 0.0, 0.0, 1.0);
                    return;
                }
                if (uDebugMode == 4) {
                    fragColor = vec4(vUv, 0.0, 1.0);
                    return;
                }
                float alpha = clamp(mask * uOverlayAlpha, 0.0, 1.0);
                vec3 outColor = mix(cam, uOverlayColor, alpha);
                fragColor = vec4(outColor, 1.0);
            }
        """
    }
}
