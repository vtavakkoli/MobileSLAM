package robotic.slam

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import robotic.slam.databinding.ActivityCalibrationBinding

class CalibrationActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCalibrationBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCalibrationBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Camera Calibration"

        val currentFocalLength = SlamPreferences.getFocalLength(this)
        binding.focalLengthInput.setText(currentFocalLength.toString())

        val currentPan = SlamPreferences.getPanCoefficient(this)
        binding.panCoeffInput.setText(currentPan.toString())

        val currentTilt = SlamPreferences.getTiltCoefficient(this)
        binding.tiltCoeffInput.setText(currentTilt.toString())

        binding.btnSaveCalibration.setOnClickListener {
            val flInput = binding.focalLengthInput.text.toString()
            val panInput = binding.panCoeffInput.text.toString()
            val tiltInput = binding.tiltCoeffInput.text.toString()

            val focalLength = flInput.toFloatOrNull()
            val pan = panInput.toFloatOrNull()
            val tilt = tiltInput.toFloatOrNull()
            
            if (focalLength != null && focalLength > 0 && pan != null && tilt != null) {
                SlamPreferences.setFocalLength(this, focalLength)
                SlamPreferences.setPanCoefficient(this, pan)
                SlamPreferences.setTiltCoefficient(this, tilt)
                Toast.makeText(this, "Calibration parameters saved!", Toast.LENGTH_SHORT).show()
                finish()
            } else {
                Toast.makeText(this, "Please enter valid numeric values", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
