package com.modul.gymai

import android.animation.ObjectAnimator
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.animation.DecelerateInterpolator
import android.widget.ProgressBar
import androidx.appcompat.app.AppCompatActivity

class SplashScreen : AppCompatActivity() {

    private val SPLASH_DURATION = 3000L // 3 detik total durasi splash

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Sembunyikan action bar jika ada
        supportActionBar?.hide()

        // Buat status bar transparan (Edge-to-Edge)
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)

        setContentView(R.layout.activity_splash_screen)

        val progressBar = findViewById<ProgressBar>(R.id.progress_loading)

        // Animasi progress bar dari 0 → 100 selama SPLASH_DURATION
        val animator = ObjectAnimator.ofInt(progressBar, "progress", 0, 100)
        animator.duration = SPLASH_DURATION
        animator.interpolator = DecelerateInterpolator()
        animator.start()

        // Pindah ke OnboardingActivity setelah splash selesai
        Handler(Looper.getMainLooper()).postDelayed({
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
        }, SPLASH_DURATION)
    }
}