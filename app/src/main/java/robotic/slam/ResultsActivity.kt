package robotic.slam

import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import robotic.slam.databinding.ActivityResultsBinding
import kotlin.math.sqrt

class ResultsActivity : AppCompatActivity() {

    private lateinit var renderer: SlamGLRenderer
    private lateinit var binding: ActivityResultsBinding
    private var previousX: Float = 0f
    private var previousY: Float = 0f
    private var lastDistance: Float = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityResultsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "3D Map History"

        renderer = SlamGLRenderer().apply {
            showBackground = false
            angleX = 78f
            angleY = -25f
            zoom = 12f
            mapDisplayScale = 2.0f
            mirrorForwardAxis = true
            cameraFrustumScale = 0.10f
            cameraDrawStride = 14
            showFeatures = true
            showPath = true
            showCameras = true
        }

        setupLayerControls()
        setupGlView()
        loadCapturedData()
    }

    private fun setupLayerControls() {
        binding.switchFeatures.setOnCheckedChangeListener { _, isChecked ->
            renderer.showFeatures = isChecked
            binding.resultsGlView.requestRender()
        }
        binding.switchPath.setOnCheckedChangeListener { _, isChecked ->
            renderer.showPath = isChecked
            binding.resultsGlView.requestRender()
        }
        binding.switchCameras.setOnCheckedChangeListener { _, isChecked ->
            renderer.showCameras = isChecked
            binding.resultsGlView.requestRender()
        }

        binding.btnToggleAxes.setOnClickListener {
            renderer.showAxes = !renderer.showAxes
            binding.resultsGlView.requestRender()
        }
    }

    private fun setupGlView() {
        binding.resultsGlView.apply {
            setEGLContextClientVersion(2)
            setRenderer(renderer)

            setOnTouchListener { v, event ->
                val x: Float = event.x
                val y: Float = event.y

                when (event.action and MotionEvent.ACTION_MASK) {
                    MotionEvent.ACTION_POINTER_DOWN -> {
                        lastDistance = getDistance(event)
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (event.pointerCount == 1) {
                            val dx: Float = x - previousX
                            val dy: Float = y - previousY
                            val panCoeff = SlamPreferences.getPanCoefficient(this@ResultsActivity)
                            val tiltCoeff = SlamPreferences.getTiltCoefficient(this@ResultsActivity)
                            renderer.angleY += dx * panCoeff
                            renderer.angleX += dy * tiltCoeff
                        } else if (event.pointerCount == 2) {
                            val newDistance = getDistance(event)
                            val delta = newDistance - lastDistance
                            renderer.zoom -= delta * 0.2f
                            if (renderer.zoom < 1f) renderer.zoom = 1f
                            lastDistance = newDistance
                        }
                        requestRender()
                    }
                    MotionEvent.ACTION_UP -> { v.performClick() }
                }
                previousX = x
                previousY = y
                true
            }
        }
    }

    private fun loadCapturedData() {
        if (SlamDataManager.hasData()) {
            renderer.clearData()
            renderer.updateData(
                SlamDataManager.capturedPoints.toFloatArray(),
                SlamDataManager.capturedPath.toFloatArray()
            )
            binding.noDataText.visibility = View.GONE
        } else {
            binding.noDataText.visibility = View.VISIBLE
            binding.noDataText.text = "No SLAM data captured yet.\nRun a mapping session first."
        }
    }

    private fun getDistance(event: MotionEvent): Float {
        val x = event.getX(0) - event.getX(1)
        val y = event.getY(0) - event.getY(1)
        return sqrt(x * x + y * y)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
