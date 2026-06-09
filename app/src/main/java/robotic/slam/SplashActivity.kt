package robotic.slam

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.animation.DecelerateInterpolator
import androidx.appcompat.app.AppCompatActivity
import robotic.slam.databinding.ActivitySplashBinding

class SplashActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySplashBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.splashContent.alpha = 0f
        binding.splashContent.translationY = 28f
        binding.splashContent.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(650)
            .setInterpolator(DecelerateInterpolator())
            .start()

        binding.splashLogo.scaleX = 0.82f
        binding.splashLogo.scaleY = 0.82f
        binding.splashLogo.animate()
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(700)
            .setInterpolator(DecelerateInterpolator())
            .start()

        binding.splashProgress.progress = 0
        binding.splashProgress.animateProgressSmoothly()

        Handler(Looper.getMainLooper()).postDelayed({
            startActivity(Intent(this, MenuActivity::class.java))
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            finish()
        }, SPLASH_DELAY_MS)
    }

    private fun android.widget.ProgressBar.animateProgressSmoothly() {
        val handler = Handler(Looper.getMainLooper())
        var value = 0
        val runnable = object : Runnable {
            override fun run() {
                value += 5
                progress = value.coerceAtMost(100)
                if (value < 100) handler.postDelayed(this, 45)
            }
        }
        handler.post(runnable)
    }

    companion object {
        private const val SPLASH_DELAY_MS = 1250L
    }
}
