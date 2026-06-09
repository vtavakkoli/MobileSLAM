package robotic.slam

import android.content.Intent
import android.os.Bundle
import android.widget.SeekBar
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import robotic.slam.databinding.ActivityMenuBinding

class MenuActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMenuBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMenuBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupDurationSettings()
        animateMenuIn()

        binding.btnStartSlam.setOnClickListener {
            startActivity(Intent(this, SlamActivity::class.java))
        }

        binding.btnViewResults.setOnClickListener {
            startActivity(Intent(this, ResultsActivity::class.java))
        }

        binding.infoCard.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("SLAM Engine")
                .setMessage(
                    "Written by Vahid Tavakkoli, 2026 " + "For education purpose." +
                    "This is a simple OpenCV ORB monocular SLAM demo for learning feature matching, camera trajectory estimation, and sparse point-cloud visualization."
                )
                .setPositiveButton("OK", null)
                .show()
        }
    }

    override fun onResume() {
        super.onResume()
        updateDurationText(SlamPreferences.getMappingDurationSeconds(this))
    }

    private fun setupDurationSettings() {
        val current = SlamPreferences.getMappingDurationSeconds(this)
        binding.durationSeekbar.max = SlamPreferences.MAX_DURATION_SECONDS - SlamPreferences.MIN_DURATION_SECONDS
        binding.durationSeekbar.progress = current - SlamPreferences.MIN_DURATION_SECONDS
        updateDurationText(current)

        binding.durationSeekbar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val seconds = SlamPreferences.MIN_DURATION_SECONDS + progress
                updateDurationText(seconds)
                if (fromUser) {
                    SlamPreferences.setMappingDurationSeconds(this@MenuActivity, seconds)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val seconds = SlamPreferences.MIN_DURATION_SECONDS + (seekBar?.progress ?: 0)
                SlamPreferences.setMappingDurationSeconds(this@MenuActivity, seconds)
                updateDurationText(seconds)
            }
        })
    }

    private fun animateMenuIn() {
        val views = listOf(
            binding.btnStartSlamCard,
            binding.settingsCard,
            binding.btnViewResultsCard,
            binding.infoCard
        )
        views.forEachIndexed { index, view ->
            view.alpha = 0f
            view.translationY = 24f
            view.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay((index * 90).toLong())
                .setDuration(420)
                .start()
        }
    }

    private fun updateDurationText(seconds: Int) {
        binding.durationValueText.text = "${seconds}s"
        binding.durationSubtitleText.text = "Mapping session length: ${seconds} seconds"
    }
}
