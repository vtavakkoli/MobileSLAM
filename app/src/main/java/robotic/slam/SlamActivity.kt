package robotic.slam

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.os.CountDownTimer
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.opencv.android.CameraBridgeViewBase
import org.opencv.android.OpenCVLoader
import org.opencv.core.Mat
import robotic.slam.databinding.ActivityMainBinding
import kotlin.math.max

class SlamActivity : AppCompatActivity(), CameraBridgeViewBase.CvCameraViewListener2 {

    private lateinit var binding: ActivityMainBinding
    private lateinit var renderer: SlamGLRenderer

    @Volatile private var isSlamRunning = false
    private var countDownTimer: CountDownTimer? = null
    private var mappingDurationSeconds: Int = SlamPreferences.DEFAULT_DURATION_SECONDS
    private var sessionEndAtMs: Long = 0L
    private var lastPreviewUpdateMs: Long = 0L
    private var hasCompletedSession: Boolean = false

    private var previousX: Float = 0f
    private var previousY: Float = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        mappingDurationSeconds = SlamPreferences.getMappingDurationSeconds(this)
        setupGLView()
        setupCameraView()
        setIdleUi()

        if (allPermissionsGranted()) {
            initOpenCV()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }

        binding.actionButton.setOnClickListener {
            if (isSlamRunning) stopSlam(showToast = true) else startSlamSession()
        }

        binding.btnToggleAxes.setOnClickListener {
            renderer.showAxes = !renderer.showAxes
        }
    }

    private fun setupCameraView() {
        binding.javaCameraView.visibility = View.VISIBLE
        binding.javaCameraView.alpha = 0.01f
        binding.javaCameraView.setCvCameraViewListener(this)
    }

    private fun initOpenCV() {
        if (OpenCVLoader.initLocal()) {
            Log.i(TAG, "OpenCV loaded successfully via initLocal")
            binding.javaCameraView.setCameraPermissionGranted()
            binding.javaCameraView.setMaxFrameSize(640, 480)
            binding.javaCameraView.enableView()
        } else {
            Log.e(TAG, "OpenCV initialization failed!")
            Toast.makeText(this, "OpenCV initialization failed!", Toast.LENGTH_LONG).show()
        }
    }

    private fun setupGLView() {
        renderer = SlamGLRenderer()
        binding.glSurfaceView.apply {
            setEGLContextClientVersion(2)
            setZOrderOnTop(false)
            setRenderer(renderer)
            renderMode = android.opengl.GLSurfaceView.RENDERMODE_CONTINUOUSLY

            setOnTouchListener { v, event ->
                val x: Float = event.x
                val y: Float = event.y
                if (event.action == MotionEvent.ACTION_MOVE) {
                    val panCoeff = SlamPreferences.getPanCoefficient(context)
                    val tiltCoeff = SlamPreferences.getTiltCoefficient(context)
                    renderer.angleY += (x - previousX) * panCoeff
                    renderer.angleX += (y - previousY) * tiltCoeff
                } else if (event.action == MotionEvent.ACTION_UP) {
                    v.performClick()
                }
                previousX = x
                previousY = y
                true
            }
        }
    }

    override fun onCameraViewStarted(width: Int, height: Int) {}
    override fun onCameraViewStopped() {}

    override fun onCameraFrame(inputFrame: CameraBridgeViewBase.CvCameraViewFrame): Mat {
        val rgba = inputFrame.rgba()
        val gray = inputFrame.gray()

        if (isSlamRunning) {
            val status = processFrameNative(gray.nativeObj, rgba.nativeObj, gray.cols(), gray.rows())
            val points = getPointCloudNative()
            val path = getCameraPathNative()

            SlamDataManager.addPoints(points)
            SlamDataManager.addPathPoint(path)
            renderer.updateData(points, path)

            maybeUpdateLivePreview(status)
        }

        return rgba
    }

    private fun maybeUpdateLivePreview(status: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastPreviewUpdateMs < 120L) {
            runOnUiThread { binding.sampleText.text = status }
            return
        }
        lastPreviewUpdateMs = now

        val width = getLivePreviewWidthNative()
        val height = getLivePreviewHeightNative()
        val pixels = getLivePreviewArgbNative()

        if (width <= 0 || height <= 0 || pixels.size != width * height) {
            runOnUiThread { binding.sampleText.text = status }
            return
        }

        val bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        runOnUiThread {
            binding.sampleText.text = status
            binding.livePreviewPlaceholder.visibility = View.GONE
            binding.livePreviewImage.setImageBitmap(bitmap)
        }
    }

    private fun startSlamSession() {
        mappingDurationSeconds = SlamPreferences.getMappingDurationSeconds(this)
        isSlamRunning = true
        hasCompletedSession = false
        sessionEndAtMs = SystemClock.elapsedRealtime() + mappingDurationSeconds * 1000L
        lastPreviewUpdateMs = 0L

        resetSlamNative()
        SlamDataManager.clear()
        renderer.clearData()

        setRunningUi(mappingDurationSeconds)

        countDownTimer?.cancel()
        countDownTimer = object : CountDownTimer(mappingDurationSeconds * 1000L + 500L, 250L) {
            override fun onTick(millisUntilFinished: Long) {
                val remainingMs = sessionEndAtMs - SystemClock.elapsedRealtime()
                if (remainingMs <= 0L) {
                    stopSlam(showToast = true)
                } else {
                    updateTimerText(max(1, ((remainingMs + 999L) / 1000L).toInt()))
                }
            }

            override fun onFinish() {
                stopSlam(showToast = true)
            }
        }.start()
    }

    private fun stopSlam(showToast: Boolean) {
        if (!isSlamRunning && binding.actionButton.text.toString().contains("RE-START")) return
        isSlamRunning = false
        countDownTimer?.cancel()
        countDownTimer = null
        hasCompletedSession = true
        setFinishedUi()
        if (showToast) {
            Toast.makeText(this, "Capture complete. Open 3D Map History to inspect layers.", Toast.LENGTH_LONG).show()
        }
    }

    private fun setIdleUi() {
        binding.actionButton.text = if (hasCompletedSession) "RE-START MAPPING" else "START MAPPING"
        binding.timerText.visibility = View.VISIBLE
        updateTimerText(mappingDurationSeconds)
        binding.sampleText.text = "Ready • duration ${mappingDurationSeconds}s • move slowly sideways"
        binding.livePreviewPlaceholder.visibility = View.VISIBLE
    }

    private fun setRunningUi(seconds: Int) {
        binding.actionButton.text = "STOP MAPPING"
        binding.timerText.visibility = View.VISIBLE
        updateTimerText(seconds)
        binding.sampleText.text = "Mapping started • keep slow sideways motion"
        binding.livePreviewPlaceholder.visibility = View.VISIBLE
    }

    private fun setFinishedUi() {
        binding.actionButton.text = "RE-START MAPPING"
        binding.timerText.visibility = View.VISIBLE
        binding.timerText.text = "00:00"
        binding.sampleText.text = "Capture complete • open 3D Map History or restart mapping"
    }

    private fun updateTimerText(seconds: Int) {
        binding.timerText.text = String.format("00:%02d", seconds.coerceAtLeast(0))
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onResume() {
        super.onResume()
        mappingDurationSeconds = SlamPreferences.getMappingDurationSeconds(this)
        if (!isSlamRunning) setIdleUi()
        if (allPermissionsGranted()) initOpenCV()
    }

    override fun onPause() {
        super.onPause()
        binding.javaCameraView.disableView()
    }

    override fun onDestroy() {
        super.onDestroy()
        binding.javaCameraView.disableView()
        countDownTimer?.cancel()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                initOpenCV()
            } else {
                Toast.makeText(this, "Camera permission required.", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    external fun processFrameNative(matAddrGray: Long, matAddrRgba: Long, width: Int, height: Int): String
    external fun resetSlamNative()
    external fun getCameraPathNative(): FloatArray
    external fun getPointCloudNative(): FloatArray
    external fun getLivePreviewWidthNative(): Int
    external fun getLivePreviewHeightNative(): Int
    external fun getLivePreviewArgbNative(): IntArray

    companion object {
        private const val TAG = "SLAM_ORB"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
        init {
            System.loadLibrary("slam")
        }
    }
}
