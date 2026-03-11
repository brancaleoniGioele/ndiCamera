package com.example.ndi_camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.CamcorderProfile
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Range
import android.util.Size
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CameraPreviewActivity : ComponentActivity() {

    private lateinit var textureView: TextureView
    private lateinit var infoTextView: TextView
    private lateinit var recButton: Button

    private var selectedSize: Size? = null
    private var selectedFps: Int = 30

    private lateinit var cameraManager: CameraManager
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var cameraId: String? = null

    private lateinit var backgroundThread: HandlerThread
    private lateinit var backgroundHandler: Handler

    private var mediaRecorder: MediaRecorder? = null
    private var currentOutputFile: File? = null
    private var isRecording = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val resolution = intent.getStringExtra("resolution")
        selectedSize = CameraUtils.sizeFromLabel(resolution) ?: Size(1280, 720)
        selectedFps = intent.getIntExtra("fps", 30)

        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        cameraId = findBackCameraId()

        textureView = TextureView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            surfaceTextureListener = surfaceListener
        }

        infoTextView = TextView(this).apply {
            text = "Pronto"
            textSize = 16f
            setPadding(24, 16, 24, 16)
            setBackgroundColor(0x66000000)
            setTextColor(0xFFFFFFFF.toInt())
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END
            ).apply {
                topMargin = 32
                marginEnd = 32
            }
        }

        recButton = Button(this).apply {
            text = "REC 5s"
            isEnabled = false
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            ).apply {
                bottomMargin = 48
            }
            setOnClickListener {
                if (!isRecording) {
                    startRecordingTest()
                }
            }
        }

        val container = FrameLayout(this).apply {
            addView(textureView)
            addView(infoTextView)
            addView(recButton)
        }

        setContentView(container)

        if (!hasPermissions()) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.CAMERA,
                    Manifest.permission.RECORD_AUDIO
                ),
                1001
            )
        }
    }

    override fun onResume() {
        super.onResume()
        startBackgroundThread()
        if (textureView.isAvailable && hasPermissions()) {
            openCamera()
        }
    }

    override fun onPause() {
        stopRecordingIfNeeded()
        closeCamera()
        stopBackgroundThread()
        super.onPause()
    }

    private fun hasPermissions(): Boolean {
        val cameraOk = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        val audioOk = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        return cameraOk && audioOk
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("Camera2RecordThread").also { it.start() }
        backgroundHandler = Handler(backgroundThread.looper)
    }

    private fun stopBackgroundThread() {
        backgroundThread.quitSafely()
        backgroundThread.join()
    }

    private fun findBackCameraId(): String? {
        for (id in cameraManager.cameraIdList) {
            val characteristics = cameraManager.getCameraCharacteristics(id)
            val lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING)
            if (lensFacing == CameraCharacteristics.LENS_FACING_BACK) return id
        }
        return null
    }

    private fun isHighSpeedSupported(cameraId: String, size: Size, fps: Int): Boolean {
    return try {
        val chars = cameraManager.getCameraCharacteristics(cameraId)

        val capabilities = chars.get(
            CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES
        ) ?: return false

        val hasConstrainedHighSpeed = capabilities.contains(
            CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO
        )

        if (!hasConstrainedHighSpeed) {
            return false
        }

        val map = chars.get(
            CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
        ) ?: return false

        val hsSizes = map.highSpeedVideoSizes ?: return false
        if (!hsSizes.contains(size)) {
            return false
        }

        val hsRanges = map.getHighSpeedVideoFpsRangesFor(size) ?: return false

        hsRanges.any { range ->
            range.lower <= fps && range.upper >= fps
        }
    } catch (e: Exception) {
        false
    }
}

    private val surfaceListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
            if (hasPermissions()) openCamera()
        }

        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}
        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true
        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
    }

    private fun openCamera() {
        val id = cameraId ?: return
        if (!hasPermissions()) return

        try {
            cameraManager.openCamera(id, cameraStateCallback, backgroundHandler)
        } catch (e: Exception) {
            runOnUiThread { infoTextView.text = "Errore apertura camera" }
            e.printStackTrace()
        }
    }

    private val cameraStateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            cameraDevice = camera
            createPreviewOnlySession()
        }

        override fun onDisconnected(camera: CameraDevice) {
            camera.close()
            cameraDevice = null
        }

        override fun onError(camera: CameraDevice, error: Int) {
            camera.close()
            cameraDevice = null
            runOnUiThread { infoTextView.text = "Errore camera: $error" }
        }
    }

    private fun createPreviewOnlySession() {
        val camera = cameraDevice ?: return
        val size = selectedSize ?: return
        val surfaceTexture = textureView.surfaceTexture ?: return

        surfaceTexture.setDefaultBufferSize(size.width, size.height)
        val previewSurface = Surface(surfaceTexture)

        try {
            val requestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(previewSurface)
                set(
                    CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                    Range(minOf(selectedFps, 30), minOf(selectedFps, 30))
                )
                set(
                    CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
                )
            }

            camera.createCaptureSession(
                listOf(previewSurface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session

                        runOnUiThread {
                            recButton.isEnabled = true
                            infoTextView.text =
                                "Pronto: ${selectedSize!!.width}x${selectedSize!!.height} @ ${selectedFps}fps"
                        }

                        try {
                            session.setRepeatingRequest(
                                requestBuilder.build(),
                                null,
                                backgroundHandler
                            )
                        } catch (e: Exception) {
                            runOnUiThread { infoTextView.text = "Errore preview" }
                            e.printStackTrace()
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        runOnUiThread {
                            infoTextView.text = "Config HS REC fallita"
                            recButton.isEnabled = true
                        }
                        stopRecordingIfNeeded()
                    }
                },
                backgroundHandler
            )
        } catch (e: Exception) {
            runOnUiThread { infoTextView.text = "Errore sessione preview" }
            e.printStackTrace()
        }
    }

    private fun startRecordingTest() {
        val camera = cameraDevice ?: return
        val id = cameraId ?: return
        val size = selectedSize ?: return
        val surfaceTexture = textureView.surfaceTexture ?: return

        val useHighSpeed = selectedFps > 30 && isHighSpeedSupported(id, size, selectedFps)

        if (selectedFps > 30 && !useHighSpeed) {
            runOnUiThread {
                infoTextView.text =
                    "High-speed non supportato su questo device/camera per ${size.width}x${size.height} @ ${selectedFps}fps"
            }
            return
        }

        runOnUiThread {
            infoTextView.text = "Tentativo REC ${size.width}x${size.height} @ ${selectedFps}fps" +
                if (useHighSpeed) " HS" else ""
        }

        try {
            currentOutputFile = buildOutputFile()
            setupMediaRecorder(currentOutputFile!!)

            surfaceTexture.setDefaultBufferSize(size.width, size.height)
            val previewSurface = Surface(surfaceTexture)
            val recordSurface = mediaRecorder!!.surface

            val template = if (useHighSpeed) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_RECORD

            val requestBuilder = camera.createCaptureRequest(template).apply {
                addTarget(previewSurface)
                addTarget(recordSurface)
                set(
                    CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                    Range(selectedFps, selectedFps)
                )
                set(
                    CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
                )
            }

            if (useHighSpeed) {
                camera.createConstrainedHighSpeedCaptureSession(
                    listOf(previewSurface, recordSurface),
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            val hsSession = session as? CameraConstrainedHighSpeedCaptureSession
                            if (hsSession == null) {
                                runOnUiThread { infoTextView.text = "HS session non disponibile" }
                                return
                            }

                            captureSession = hsSession

                            try {
                                val burstList = hsSession.createHighSpeedRequestList(requestBuilder.build())
                                hsSession.setRepeatingBurst(burstList, null, backgroundHandler)
                                try {
                                    mediaRecorder?.start()
                                } catch (e: Exception) {
                                    runOnUiThread {
                                        infoTextView.text = "start() fallita: ${e.javaClass.simpleName}"
                                    }
                                    throw e
                                }
                                isRecording = true

                                runOnUiThread {
                                    infoTextView.text =
                                        "REC HS ${selectedSize!!.width}x${selectedSize!!.height} @ ${selectedFps}fps"
                                    recButton.isEnabled = false
                                }

                                textureView.postDelayed({
                                    stopRecordingIfNeeded()
                                }, 5000)
                            } catch (e: Exception) {
                                runOnUiThread { infoTextView.text = "Errore avvio REC HS" }
                                e.printStackTrace()
                                stopRecordingIfNeeded()
                            }
                        }

                        override fun onConfigureFailed(session: CameraCaptureSession) {
                            runOnUiThread {
                                infoTextView.text = "Config HS REC fallita"
                                recButton.isEnabled = true
                            }
                            stopRecordingIfNeeded()
                        }
                    },
                    backgroundHandler
                )
            } else {
                camera.createCaptureSession(
                    listOf(previewSurface, recordSurface),
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            captureSession = session

                            try {
                                session.setRepeatingRequest(
                                    requestBuilder.build(),
                                    null,
                                    backgroundHandler
                                )
                                mediaRecorder?.start()
                                isRecording = true

                                runOnUiThread {
                                    infoTextView.text =
                                        "REC ${selectedSize!!.width}x${selectedSize!!.height} @ ${selectedFps}fps"
                                    recButton.isEnabled = false
                                }

                                textureView.postDelayed({
                                    stopRecordingIfNeeded()
                                }, 5000)
                            } catch (e: Exception) {
                                runOnUiThread { infoTextView.text = "Errore avvio REC" }
                                e.printStackTrace()
                                stopRecordingIfNeeded()
                            }
                        }

                        override fun onConfigureFailed(session: CameraCaptureSession) {
                            runOnUiThread {
                                infoTextView.text = "Config HS REC fallita"
                                recButton.isEnabled = true
                            }
                            stopRecordingIfNeeded()
                        }
                    },
                    backgroundHandler
                )
            }
        } catch (e: Exception) {
            runOnUiThread { infoTextView.text = "Errore setup registrazione" }
            e.printStackTrace()
            stopRecordingIfNeeded()
        }
    }

    private fun setupMediaRecorder(outputFile: File) {
        val size = selectedSize ?: Size(1280, 720)
        val fps = selectedFps

        mediaRecorder?.release()
        mediaRecorder = MediaRecorder()

        val bitRate = when {
            size.width >= 3840 || size.height >= 2160 -> 35_000_000
            size.width >= 2560 || size.height >= 1440 -> 24_000_000
            size.width >= 1920 || size.height >= 1080 -> 16_000_000
            else -> 10_000_000
        }

        mediaRecorder?.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)

            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)

            setVideoEncodingBitRate(bitRate)
            setAudioEncodingBitRate(128_000)
            setAudioSamplingRate(48_000)

            setVideoFrameRate(fps)
            setVideoSize(size.width, size.height)

            setOutputFile(outputFile.absolutePath)

            try {
                prepare()
            } catch (e: Exception) {
                runOnUiThread {
                    infoTextView.text = "prepare() fallita: ${e.javaClass.simpleName}"
                }
                throw e
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun chooseProfile(size: Size, fps: Int): CamcorderProfile? {
        val idInt = cameraId?.toIntOrNull() ?: return null

        val quality = when {
            size.width == 3840 && size.height == 2160 -> CamcorderProfile.QUALITY_2160P
            size.width == 2560 && size.height == 1440 -> CamcorderProfile.QUALITY_QHD
            size.width == 1920 && size.height == 1080 -> CamcorderProfile.QUALITY_1080P
            size.width == 1280 && size.height == 720 -> CamcorderProfile.QUALITY_720P
            else -> CamcorderProfile.QUALITY_HIGH
        }

        return if (CamcorderProfile.hasProfile(idInt, quality)) {
            CamcorderProfile.get(idInt, quality)
        } else {
            null
        }
    }

    private fun buildOutputFile(): File {
        val moviesDir = getExternalFilesDir("Movies") ?: filesDir
        if (!moviesDir.exists()) moviesDir.mkdirs()

        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return File(moviesDir, "test_${stamp}_${selectedFps}fps.mp4")
    }

    private fun stopRecordingIfNeeded() {
        if (!isRecording) return

        try {
            mediaRecorder?.stop()
        } catch (_: Exception) {
        }

        try {
            mediaRecorder?.reset()
            mediaRecorder?.release()
        } catch (_: Exception) {
        }

        mediaRecorder = null
        isRecording = false

        runOnUiThread {
            infoTextView.text = "Salvato: ${currentOutputFile?.name ?: "file"}"
            recButton.isEnabled = true
        }

        createPreviewOnlySession()
    }

    private fun closeCamera() {
        captureSession?.close()
        captureSession = null

        cameraDevice?.close()
        cameraDevice = null
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == 1001 &&
            grantResults.isNotEmpty() &&
            grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        ) {
            if (textureView.isAvailable) openCamera()
        } else {
            finish()
        }
    }
}