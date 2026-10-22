package com.fold7.cam360

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.opengl.EGL14
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.EGLExt
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.Log
import android.view.Gravity
import android.view.Surface
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.Toast
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.Executor
import javax.microedition.khronos.egl.EGLConfig

class MainActivity : Activity() {

    private lateinit var glSurfaceView: GLSurfaceView
    private lateinit var recordBtn: Button
    private lateinit var renderer: StitchRenderer
    private var isRecording = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = FrameLayout(this)
        renderer = StitchRenderer(this@MainActivity)
        glSurfaceView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2)
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }

        recordBtn = Button(this).apply {
            text = "開始錄製 360 影片"
            setOnClickListener { toggleRecording() }
        }

        val btnParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            bottomMargin = 60
        }

        root.addView(glSurfaceView)
        root.addView(recordBtn, btnParams)
        setContentView(root)

        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO), 100)
        }
    }

    private fun toggleRecording() {
        if (!isRecording) {
            val outFile = File(externalCacheDir, "temp_360.mp4")
            val ok = renderer.startRecording(outFile)
            if (ok) {
                isRecording = true
                recordBtn.text = "停止並儲存 360 影片"
            } else {
                Toast.makeText(this, "錄影啟動失敗", Toast.LENGTH_SHORT).show()
            }
        } else {
            recordBtn.isEnabled = false
            recordBtn.text = "合成 360 Metadata 中..."
            renderer.stopRecording { savedFile ->
                runOnUiThread {
                    injectSphericalMetadataAndSaveToGallery(savedFile)
                    isRecording = false
                    recordBtn.isEnabled = true
                    recordBtn.text = "開始錄製 360 影片"
                    Toast.makeText(this, "360 影片已儲存至相簿 Movies/Fold7_360！", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        glSurfaceView.onPause()
    }

    override fun onResume() {
        super.onResume()
        glSurfaceView.onResume()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            Log.i("Fold360", "Camera permission granted, opening cameras")
            glSurfaceView.queueEvent { renderer.openDualCameras() }
        }
    }

    // 寫入 Google Spherical V1 UUID Metadata 並複製到公開相簿
    private fun injectSphericalMetadataAndSaveToGallery(sourceFile: File) {
        try {
            val finalFile = File(externalCacheDir, "final_360.mp4")
            injectSphericalMetadata(sourceFile, finalFile)

            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, "Fold7_360_${System.currentTimeMillis()}.mp4")
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Fold7_360")
            }
            val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return
            contentResolver.openOutputStream(uri)?.use { outStream ->
                finalFile.inputStream().use { inStream ->
                    inStream.copyTo(outStream)
                }
            }
        } catch (e: Exception) {
            Log.e("Fold360", "Save error", e)
        }
    }

    // Google Spherical Video V1 的 spherical uuid box
    private fun injectSphericalMetadata(src: File, dst: File) {
        // UUID for Google Spherical Video V1
        val sphericalUuid = byteArrayOf(
            0x3D.toByte(), 0x1F.toByte(), 0xD8.toByte(), 0x6B.toByte(),
            0x7D.toByte(), 0x3D.toByte(), 0x44.toByte(), 0x55.toByte(),
            0x8A.toByte(), 0x4F.toByte(), 0x9C.toByte(), 0x3F.toByte(),
            0x0D.toByte(), 0x4F.toByte(), 0x8F.toByte(), 0x0E.toByte()
        )
        // Spherical metadata: projection_type = equirectangular (0), padding, bounds
        // Version 1: [4 bytes projection_type][12 bytes bounds/padding][...]
        val payload = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN)
        payload.putInt(0)            // equirectangular
        payload.putInt(0)            // reserved / bounds
        payload.putInt(0)
        payload.putInt(0)
        payload.putInt(0)
        val payloadBytes = payload.array()

        val boxSize = 16 + 16 + payloadBytes.size // header(8) + uuid(16) + payload(20)
        val box = ByteBuffer.allocate(boxSize).order(ByteOrder.BIG_ENDIAN)
        box.putInt(boxSize)
        box.put("uuid".toByteArray(Charsets.US_ASCII))
        box.put(sphericalUuid)
        box.put(payloadBytes)

        FileInputStream(src).use { input ->
            FileOutputStream(dst).use { output ->
                val buf = ByteArray(4096)
                var read: Int
                // 讀入 ftyp box 頭部 (8 bytes)
                val ftypHeader = ByteArray(8)
                var off = 0
                while (off < 8) {
                    val r = input.read(ftypHeader, off, 8 - off)
                    if (r < 0) break
                    off += r
                }
                if (off == 8) {
                    val ftypSize = ((ftypHeader[0].toInt() and 0xFF) shl 24) or
                            ((ftypHeader[1].toInt() and 0xFF) shl 16) or
                            ((ftypHeader[2].toInt() and 0xFF) shl 8) or
                            (ftypHeader[3].toInt() and 0xFF)
                    output.write(ftypHeader)
                    // 複製剩餘 ftyp box 內容
                    var remaining = ftypSize - 8
                    while (remaining > 0) {
                        read = input.read(buf, 0, minOf(buf.size, remaining))
                        if (read < 0) break
                        output.write(buf, 0, read)
                        remaining -= read
                    }
                } else {
                    // 沒有 ftyp，直接從頭複製
                    while (input.read(buf).also { read = it } >= 0) {
                        output.write(buf, 0, read)
                    }
                }
                // 在 ftyp 之後插入 spherical uuid box
                output.write(box.array())
                // 複製其餘內容 (moov, mdat...)
                while (input.read(buf).also { read = it } >= 0) {
                    output.write(buf, 0, read)
                }
            }
        }
    }
}

class StitchRenderer(private val context: Context) : GLSurfaceView.Renderer {

    private var program = 0
    private val texIds = IntArray(2)
    private lateinit var backTex: SurfaceTexture
    private lateinit var frontTex: SurfaceTexture

    private val cameraThread = HandlerThread("CamThread").apply { start() }
    private val cameraHandler = Handler(cameraThread.looper)
    private val cameraExecutor = Executor { cameraHandler.post(it) }

    // 頂點座標 (全螢幕 Quad)
    private val vertices = floatArrayOf(
        -1f, -1f, 0f, 0f,
        1f, -1f, 1f, 0f,
        -1f, 1f, 0f, 1f,
        1f, 1f, 1f, 1f
    )
    private val vertexBuffer: FloatBuffer = ByteBuffer.allocateDirect(vertices.size * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(vertices); position(0) }

    // ---- 錄影狀態 ----
    @Volatile private var isRecording = false
    @Volatile private var encoderReady = false
    private var mediaCodec: MediaCodec? = null
    private var mediaMuxer: MediaMuxer? = null
    private var trackIndex = -1
    private var muxerStarted = false
    private var outputFile: File? = null
    private var encoderSurface: Surface? = null
    private var eglDisplay: EGLDisplay? = null
    private var eglContext: EGLContext? = null
    private var eglSurface: EGLSurface? = null
    private var viewSurface: EGLSurface? = null
    private var framePending = false
    private var renderTargetsDirty = true

    private val vertexShaderCode = """
        attribute vec2 aPosition;
        attribute vec2 aTexCoord;
        varying vec2 vTexCoord;
        void main() {
            gl_Position = vec4(aPosition, 0.0, 1.0);
            vTexCoord = aTexCoord;
        }
    """.trimIndent()

    private val fragmentShaderCode = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;
        varying vec2 vTexCoord;
        uniform samplerExternalOES sBackCam;
        uniform samplerExternalOES sFrontCam;
        const float PI = 3.14159265;
        const float BACK_FOV_H = 123.0 * PI / 180.0;
        const float BACK_FOV_V = 95.0 * PI / 180.0;
        const float FRONT_FOV_H = 85.0 * PI / 180.0;
        const float FRONT_FOV_V = 65.0 * PI / 180.0;

        void main() {
            float theta = (vTexCoord.x - 0.5) * 2.0 * PI;
            float phi = (vTexCoord.y - 0.5) * PI;
            vec4 color = vec4(0.0, 0.0, 0.0, 1.0);

            if (abs(theta) <= BACK_FOV_H * 0.5 && abs(phi) <= BACK_FOV_V * 0.5) {
                vec2 uv = vec2((theta / BACK_FOV_H) + 0.5, 1.0 - ((phi / BACK_FOV_V) + 0.5));
                color = texture2D(sBackCam, uv);
            } else {
                float bTheta = theta > 0.0 ? (theta - PI) : (theta + PI);
                if (abs(bTheta) <= FRONT_FOV_H * 0.5 && abs(phi) <= FRONT_FOV_V * 0.5) {
                    vec2 uv = vec2((bTheta / FRONT_FOV_H) + 0.5, 1.0 - ((phi / FRONT_FOV_V) + 0.5));
                    color = texture2D(sFrontCam, uv);
                }
            }
            gl_FragColor = color;
        }
    """.trimIndent()

    override fun onSurfaceCreated(gl: javax.microedition.khronos.opengles.GL10?, config: javax.microedition.khronos.egl.EGLConfig?) {
        GLES20.glGenTextures(2, texIds, 0)
        for (i in 0..1) {
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texIds[i])
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        }

        backTex = SurfaceTexture(texIds[0]).apply { setDefaultBufferSize(1920, 1080) }
        frontTex = SurfaceTexture(texIds[1]).apply { setDefaultBufferSize(1920, 1080) }

        val vShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexShaderCode)
        val fShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderCode)
        program = GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, vShader)
            GLES20.glAttachShader(it, fShader)
            GLES20.glLinkProgram(it)
        }
        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] == 0) {
            Log.e("Fold360", "Program link error: ${GLES20.glGetProgramInfoLog(program)}")
        } else {
            Log.i("Fold360", "Shader program linked OK")
        }

        openDualCameras()
    }

    override fun onSurfaceChanged(gl: javax.microedition.khronos.opengles.GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: javax.microedition.khronos.opengles.GL10?) {
        try {
            backTex.updateTexImage()
        } catch (e: Exception) {
            Log.w("Fold360", "backTex update fail: ${e.message}")
        }
        try {
            frontTex.updateTexImage()
        } catch (e: Exception) {
            Log.w("Fold360", "frontTex update fail: ${e.message}")
        }

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)

        val posLoc = GLES20.glGetAttribLocation(program, "aPosition")
        val texLoc = GLES20.glGetAttribLocation(program, "aTexCoord")

        vertexBuffer.position(0)
        GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT, false, 16, vertexBuffer)
        GLES20.glEnableVertexAttribArray(posLoc)

        vertexBuffer.position(2)
        GLES20.glVertexAttribPointer(texLoc, 2, GLES20.GL_FLOAT, false, 16, vertexBuffer)
        GLES20.glEnableVertexAttribArray(texLoc)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texIds[0])
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "sBackCam"), 0)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texIds[1])
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "sFrontCam"), 1)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        // 錄影：將同一幀繪製到 encoder surface
        if (isRecording) {
            if (!encoderReady) {
                setupEGLSurfaceOnGLThread()
                encoderReady = true
            }
            drawToEncoder()
        }
    }

    // ---- 錄影：EGL offscreen surface + MediaCodec input surface ----
    @Synchronized
    fun startRecording(file: File): Boolean {
        if (isRecording) return false
        outputFile = file
        try {
            // MediaCodec + Muxer setup 喺 caller thread（UI thread），
            // 唔喺 GL thread 做 blocking 初始化（修 freeze）
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 1920, 1080).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, 12_000_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                encoderSurface = createInputSurface()
                start()
            }
            mediaMuxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxerStarted = false
            trackIndex = -1
            framePending = false
            isRecording = true
            encoderReady = false  // EGL surface 未建立，等 onDrawFrame
            Log.i("Fold360", "Encoder ready, waiting EGL surface setup on GL thread")
            return true
        } catch (e: Exception) {
            Log.e("Fold360", "startRecording failed", e)
            releaseEncoder()
            return false
        }
    }

    @Synchronized
    fun stopRecording(onComplete: (File) -> Unit) {
        if (!isRecording) return
        isRecording = false
        try {
            drainEncoder()
            releaseEncoder()
            outputFile?.let { onComplete(it) }
        } catch (e: Exception) {
            Log.e("Fold360", "stopRecording failed", e)
        }
    }

    private fun setupEGLSurfaceOnGLThread() {
        if (eglSurface != null) return
        try {
            // 用 GLSurfaceView 當前 context（GL thread）作為共享 context，
            // 令 encoder surface 可以讀到 preview 嘅 texture（修黑畫面）
            val display = EGL14.eglGetCurrentDisplay()
            val sharedContext = EGL14.eglGetCurrentContext()
            eglDisplay = display
            viewSurface = EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW)

            val eglConfig = chooseConfig(display)
            eglContext = EGL14.eglCreateContext(
                display, eglConfig, sharedContext,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0
            )
            eglSurface = EGL14.eglCreateWindowSurface(display, eglConfig, encoderSurface!!, intArrayOf(EGL14.EGL_NONE), 0)
            Log.i("Fold360", "EGL surface ready")
        } catch (e: Exception) {
            Log.e("Fold360", "EGL setup failed", e)
        }
    }

    private fun chooseConfig(display: EGLDisplay): android.opengl.EGLConfig {
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGLExt.EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
        val num = IntArray(1)
        EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, num, 0)
        return configs[0]!!
    }

    private fun drawToEncoder() {
        val surface = eglSurface ?: return
        val display = eglDisplay ?: return
        val context = eglContext ?: return
        if (!EGL14.eglMakeCurrent(display, surface, surface, context)) {
            Log.e("Fold360", "eglMakeCurrent failed")
            return
        }
        // 重新綁定 texture 並繪製（encoder surface 是獨立 context）
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)

        val posLoc = GLES20.glGetAttribLocation(program, "aPosition")
        val texLoc = GLES20.glGetAttribLocation(program, "aTexCoord")
        vertexBuffer.position(0)
        GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT, false, 16, vertexBuffer)
        GLES20.glEnableVertexAttribArray(posLoc)
        vertexBuffer.position(2)
        GLES20.glVertexAttribPointer(texLoc, 2, GLES20.GL_FLOAT, false, 16, vertexBuffer)
        GLES20.glEnableVertexAttribArray(texLoc)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texIds[0])
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "sBackCam"), 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texIds[1])
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "sFrontCam"), 1)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        EGLExt.eglPresentationTimeANDROID(display, surface, System.nanoTime())
        EGL14.eglSwapBuffers(display, surface)

        // 還原 GLSurfaceView 嘅 surface，避免干擾下一幀 preview
        val view = viewSurface
        if (view != null) {
            EGL14.eglMakeCurrent(display, view, view, context)
        }

        drainEncoderIfNeeded()
    }

    private fun drainEncoderIfNeeded() {
        val codec = mediaCodec ?: return
        val bufferInfo = MediaCodec.BufferInfo()
        while (true) {
            val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
            when {
                outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (!muxerStarted) {
                        trackIndex = mediaMuxer!!.addTrack(codec.outputFormat)
                        mediaMuxer!!.start()
                        muxerStarted = true
                    }
                }
                outputIndex >= 0 -> {
                    val buffer = codec.getOutputBuffer(outputIndex) ?: continue
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        bufferInfo.size = 0
                    }
                    if (bufferInfo.size > 0 && muxerStarted) {
                        buffer.position(bufferInfo.offset)
                        buffer.limit(bufferInfo.offset + bufferInfo.size)
                        mediaMuxer!!.writeSampleData(trackIndex, buffer, bufferInfo)
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        return
                    }
                }
            }
        }
    }

    private fun drainEncoder() {
        val codec = mediaCodec ?: return
        codec.signalEndOfInputStream()
        val bufferInfo = MediaCodec.BufferInfo()
        while (true) {
            val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
            when {
                outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (!muxerStarted) {
                        trackIndex = mediaMuxer!!.addTrack(codec.outputFormat)
                        mediaMuxer!!.start()
                        muxerStarted = true
                    }
                }
                outputIndex >= 0 -> {
                    val buffer = codec.getOutputBuffer(outputIndex) ?: continue
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        bufferInfo.size = 0
                    }
                    if (bufferInfo.size > 0 && muxerStarted) {
                        buffer.position(bufferInfo.offset)
                        buffer.limit(bufferInfo.offset + bufferInfo.size)
                        mediaMuxer!!.writeSampleData(trackIndex, buffer, bufferInfo)
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        }
    }

    private fun releaseEncoder() {
        try {
            if (muxerStarted && mediaMuxer != null) {
                mediaMuxer!!.stop()
            }
        } catch (e: Exception) {
            Log.e("Fold360", "muxer stop error", e)
        }
        try { mediaMuxer?.release() } catch (_: Exception) {}
        try { mediaCodec?.stop() } catch (_: Exception) {}
        try { mediaCodec?.release() } catch (_: Exception) {}
        try { encoderSurface?.release() } catch (_: Exception) {}
        val dpy = eglDisplay
        if (eglSurface != null && dpy != null) {
            EGL14.eglDestroySurface(dpy, eglSurface)
        }
        if (eglContext != null && dpy != null) {
            EGL14.eglDestroyContext(dpy, eglContext)
        }
        if (dpy != null) {
            EGL14.eglTerminate(dpy)
        }
        mediaMuxer = null
        mediaCodec = null
        encoderSurface = null
        eglSurface = null
        eglContext = null
        eglDisplay = null
        viewSurface = null
        muxerStarted = false
        trackIndex = -1
        encoderReady = false
    }

    // ---- 雙鏡頭開啟 ----
    @SuppressLint("MissingPermission")
    fun openDualCameras() {
        if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Log.w("Fold360", "Camera permission not granted yet, skip opening")
            return
        }
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        var backId = "0"
        var frontId = "1"
        var foundConcurrent = false

        for (set in manager.concurrentCameraIds) {
            var b: String? = null
            var f: String? = null
            for (id in set) {
                val facing = manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING)
                if (facing == CameraCharacteristics.LENS_FACING_BACK) b = id
                if (facing == CameraCharacteristics.LENS_FACING_FRONT) f = id
            }
            if (b != null && f != null) {
                backId = b
                frontId = f
                foundConcurrent = true
                break
            }
        }
        Log.i("Fold360", "Dual camera: back=$backId front=$frontId concurrent=$foundConcurrent")

        val backSurface = Surface(backTex)
        val frontSurface = Surface(frontTex)

        manager.openCamera(backId, object : CameraDevice.StateCallback() {
            override fun onOpened(cam1: CameraDevice) {
                Log.i("Fold360", "Back camera opened: $backId")
                startSession(cam1, backSurface)
                manager.openCamera(frontId, object : CameraDevice.StateCallback() {
                    override fun onOpened(cam2: CameraDevice) {
                        Log.i("Fold360", "Front camera opened: $frontId")
                        startSession(cam2, frontSurface)
                    }
                    override fun onDisconnected(c: CameraDevice) { Log.w("Fold360", "Front disconnected"); c.close() }
                    override fun onError(c: CameraDevice, e: Int) { Log.e("Fold360", "Front open error: $e"); c.close() }
                }, cameraHandler)
            }
            override fun onDisconnected(c: CameraDevice) { Log.w("Fold360", "Back disconnected"); c.close() }
            override fun onError(c: CameraDevice, e: Int) { Log.e("Fold360", "Back open error: $e"); c.close() }
        }, cameraHandler)
    }

    private fun startSession(cam: CameraDevice, surface: Surface) {
        val config = SessionConfiguration(
            SessionConfiguration.SESSION_REGULAR,
            listOf(OutputConfiguration(surface)),
            cameraExecutor,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    val req = cam.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply { addTarget(surface) }
                    session.setRepeatingRequest(req.build(), null, cameraHandler)
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {}
            }
        )
        cam.createCaptureSession(config)
    }

    private fun loadShader(type: Int, code: String): Int {
        return GLES20.glCreateShader(type).also {
            GLES20.glShaderSource(it, code)
            GLES20.glCompileShader(it)
            val status = IntArray(1)
            GLES20.glGetShaderiv(it, GLES20.GL_COMPILE_STATUS, status, 0)
            if (status[0] == 0) {
                Log.e("Fold360", "Shader compile error: ${GLES20.glGetShaderInfoLog(it)}")
            }
        }
    }
}
