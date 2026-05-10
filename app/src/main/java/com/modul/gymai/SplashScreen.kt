package com.modul.gymai

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.graphics.Color
import android.graphics.RectF
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.modul.gymai.ui.AnimatedGradientView
import com.modul.gymai.ui.GlowRingLoadingView
import com.modul.gymai.ui.SplashParticleView

class SplashScreen : AppCompatActivity() {

    companion object {
        private const val SPLASH_DURATION = 9000L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        setContentView(R.layout.activity_splash_screen)

        val logo        = findViewById<ImageView>(R.id.iv_logo)
        val appName     = findViewById<TextView>(R.id.tv_app_name)
        val tagline     = findViewById<TextView>(R.id.tv_tagline)
        val glowRing    = findViewById<GlowRingLoadingView>(R.id.glow_ring_loader)
        val loadingPct  = findViewById<TextView>(R.id.tv_loading_pct)
        val copyright   = findViewById<TextView>(R.id.tv_copyright)
        val glowView    = findViewById<View>(R.id.glow_view)
        val animatedBg  = findViewById<AnimatedGradientView>(R.id.animated_bg)
        val particleView = findViewById<SplashParticleView>(R.id.particle_canvas)

        // ── Mulai background gradient segera ──
        animatedBg.startAnimation()

        // ── Reset semua elemen ke state invisible ──
        logo.alpha       = 0f;  logo.scaleX       = 0f;   logo.scaleY       = 0f
        appName.alpha    = 0f;  appName.translationY  = 50f
        tagline.alpha    = 0f;  tagline.translationY  = 30f;  tagline.translationX = 0f
        glowRing.alpha   = 0f;  glowRing.scaleX   = 0.3f; glowRing.scaleY   = 0.3f
        loadingPct.alpha = 0f
        copyright.alpha  = 0f
        glowView.alpha   = 0f;  glowView.scaleX   = 0.6f; glowView.scaleY   = 0.6f

        // ══════════════════════════════════════════════════════
        // BEAT 1 (T=150ms): LOGO — dramatic entrance dari scale=0
        // Spring kuat: overshoot → settle, feel "pop" yang memuaskan
        // ══════════════════════════════════════════════════════
        val logoScaleX = ObjectAnimator.ofFloat(logo, "scaleX", 0f, 1.15f, 0.93f, 1f).apply {
            duration = 950; interpolator = DecelerateInterpolator(2.2f)
        }
        val logoScaleY = ObjectAnimator.ofFloat(logo, "scaleY", 0f, 1.15f, 0.93f, 1f).apply {
            duration = 950; interpolator = DecelerateInterpolator(2.2f)
        }
        val logoAlpha = ObjectAnimator.ofFloat(logo, "alpha", 0f, 1f).apply {
            duration = 400
        }
        val logoEntrance = AnimatorSet().apply {
            playTogether(logoScaleX, logoScaleY, logoAlpha)
            startDelay = 150
        }

        // ══════════════════════════════════════════════════════
        // BEAT 2 (T=900ms): BLOOM FLASH — "impact moment" saat logo landing
        // glowView meledak terang lalu settle — efek dramatis satu kali
        // ══════════════════════════════════════════════════════
        val bloomScaleX = ObjectAnimator.ofFloat(glowView, "scaleX", 0.6f, 1.35f, 1.0f).apply {
            duration = 1000; interpolator = DecelerateInterpolator(2f)
        }
        val bloomScaleY = ObjectAnimator.ofFloat(glowView, "scaleY", 0.6f, 1.35f, 1.0f).apply {
            duration = 1000; interpolator = DecelerateInterpolator(2f)
        }
        val bloomAlpha = ObjectAnimator.ofFloat(glowView, "alpha", 0f, 0.95f, 0.38f).apply {
            duration = 1000; interpolator = DecelerateInterpolator(1.8f)
        }
        val bloomFlash = AnimatorSet().apply {
            playTogether(bloomScaleX, bloomScaleY, bloomAlpha)
            startDelay = 900
        }

        // Glow sustained pulse setelah bloom settle (T=2000ms)
        val glowPulseAlpha = ObjectAnimator.ofFloat(glowView, "alpha", 0.38f, 0.14f).apply {
            duration = 2000; repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE
            startDelay = 2000
        }
        val glowPulseScaleX = ObjectAnimator.ofFloat(glowView, "scaleX", 1.0f, 1.18f).apply {
            duration = 2000; repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE
            startDelay = 2000
        }
        val glowPulseScaleY = ObjectAnimator.ofFloat(glowView, "scaleY", 1.0f, 1.18f).apply {
            duration = 2000; repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE
            startDelay = 2000
        }

        // ══════════════════════════════════════════════════════
        // BEAT 3 (T=1100ms): LOGO ALIVE — float + wobble + breathe (sustained)
        // ══════════════════════════════════════════════════════
        val logoFloat = ObjectAnimator.ofFloat(logo, "translationY", 0f, -14f).apply {
            duration = 1800; repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator(); startDelay = 1100
        }
        val logoWobble = ObjectAnimator.ofFloat(logo, "rotation", 0f, 2f, 0f, -2f, 0f).apply {
            duration = 4000; repeatMode = ValueAnimator.RESTART; repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator(); startDelay = 1100
        }
        val logoPulseX = ObjectAnimator.ofFloat(logo, "scaleX", 1f, 1.04f).apply {
            duration = 2200; repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE
            startDelay = 1100
        }
        val logoPulseY = ObjectAnimator.ofFloat(logo, "scaleY", 1f, 1.04f).apply {
            duration = 2200; repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE
            startDelay = 1100
        }

        // ══════════════════════════════════════════════════════
        // BEAT 4 (T=1150ms): APP NAME — slide UP + fade + scale + typewriter
        // Terasa seperti elemen "naik" dari logo — natural dan hidup
        // ══════════════════════════════════════════════════════
        val fullAppName = "GymPose AI"
        appName.text = ""

        val appNameSlideY = ObjectAnimator.ofFloat(appName, "translationY", 50f, 0f).apply {
            duration = 700; interpolator = DecelerateInterpolator(2.5f); startDelay = 1150
        }
        val appNameAlpha = ObjectAnimator.ofFloat(appName, "alpha", 0f, 1f).apply {
            duration = 500; startDelay = 1150
        }
        val appNameScaleX = ObjectAnimator.ofFloat(appName, "scaleX", 0.85f, 1f).apply {
            duration = 700; interpolator = DecelerateInterpolator(2f); startDelay = 1150
        }
        val appNameScaleY = ObjectAnimator.ofFloat(appName, "scaleY", 0.85f, 1f).apply {
            duration = 700; interpolator = DecelerateInterpolator(2f); startDelay = 1150
        }

        // Typewriter — 100ms/karakter, terasa deliberate
        val typewriterAnimator = ValueAnimator.ofInt(0, fullAppName.length).apply {
            duration = fullAppName.length * 100L
            startDelay = 1250
            interpolator = LinearInterpolator()
            addUpdateListener { anim ->
                val charCount = anim.animatedValue as Int
                val displayText = fullAppName.substring(0, charCount)
                val cursorVisible = (System.currentTimeMillis() / 280) % 2 == 0L
                val cursor = if (charCount < fullAppName.length && cursorVisible) "▎" else ""
                appName.text = "$displayText$cursor"
            }
        }
        val typewriterEnd = 1250 + fullAppName.length * 100L
        Handler(Looper.getMainLooper()).postDelayed({ appName.text = fullAppName }, typewriterEnd + 80)

        // Subtle glow shimmer setelah typewriter selesai
        val appNameGlow = ObjectAnimator.ofFloat(appName, "alpha", 1f, 0.70f).apply {
            duration = 2200; repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE
            startDelay = typewriterEnd
        }

        // ══════════════════════════════════════════════════════
        // BEAT 5 (T=1750ms): TAGLINE — fade + slide UP elegan
        // Lebih premium dari marquee kanan — terasa satu keluarga dengan app name
        // ══════════════════════════════════════════════════════
        val taglineSlideY = ObjectAnimator.ofFloat(tagline, "translationY", 30f, 0f).apply {
            duration = 600; interpolator = DecelerateInterpolator(2.5f); startDelay = 1750
        }
        val taglineAlpha = ObjectAnimator.ofFloat(tagline, "alpha", 0f, 1f).apply {
            duration = 500; startDelay = 1750
        }

        // ══════════════════════════════════════════════════════
        // BEAT 6 (T=2200ms): GLOW RING — spring pop kuat + heartbeat loop
        // Overshoot besar (2.8f) → snap yang memuaskan
        // ══════════════════════════════════════════════════════
        val ringAlpha = ObjectAnimator.ofFloat(glowRing, "alpha", 0f, 1f).apply {
            duration = 500; startDelay = 2200
        }
        val ringScaleX = ObjectAnimator.ofFloat(glowRing, "scaleX", 0.3f, 1f).apply {
            duration = 700; startDelay = 2200; interpolator = OvershootInterpolator(2.8f)
        }
        val ringScaleY = ObjectAnimator.ofFloat(glowRing, "scaleY", 0.3f, 1f).apply {
            duration = 700; startDelay = 2200; interpolator = OvershootInterpolator(2.8f)
        }
        // Heartbeat pulse setelah ring muncul (T=3000ms)
        val ringPulseX = ObjectAnimator.ofFloat(glowRing, "scaleX", 1f, 1.05f).apply {
            duration = 1600; repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE
            startDelay = 3000
        }
        val ringPulseY = ObjectAnimator.ofFloat(glowRing, "scaleY", 1f, 1.05f).apply {
            duration = 1600; repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE
            startDelay = 3000
        }
        Handler(Looper.getMainLooper()).postDelayed({ glowRing.startAnimation() }, 2200)

        // ══════════════════════════════════════════════════════
        // BEAT 7 (T=2500ms): LOADING TEXT + PROGRESS
        // ══════════════════════════════════════════════════════
        val loadingAlpha = ObjectAnimator.ofFloat(loadingPct, "alpha", 0f, 1f).apply {
            duration = 400; startDelay = 2500
        }
        val loadingDots = ValueAnimator.ofInt(0, 3).apply {
            duration = 800; repeatCount = ValueAnimator.INFINITE; repeatMode = ValueAnimator.RESTART
            startDelay = 2500
            addUpdateListener {
                val dots = ".".repeat((it.animatedValue as Int) + 1)
                loadingPct.text = "Memuat$dots"
            }
        }
        val progressAnimator = ValueAnimator.ofFloat(0f, 100f).apply {
            duration = SPLASH_DURATION - 2600
            startDelay = 2600
            interpolator = DecelerateInterpolator(1.2f)
            addUpdateListener { glowRing.setProgress(it.animatedValue as Float) }
        }

        // ══════════════════════════════════════════════════════
        // BEAT 8 (T=2800ms): COPYRIGHT — whisper fade
        // ══════════════════════════════════════════════════════
        val copyrightAlpha = ObjectAnimator.ofFloat(copyright, "alpha", 0f, 0.75f).apply {
            duration = 1000; startDelay = 2800
        }

        // ══════════════════════════════════════════════════════
        // PARTICLES — mulai bersamaan dengan bloom flash (T=900ms)
        // ══════════════════════════════════════════════════════
        Handler(Looper.getMainLooper()).postDelayed({
            particleView.startAnimation()
            particleView.post {
                val loc   = IntArray(2)
                var left  = Float.MAX_VALUE;  var top    = Float.MAX_VALUE
                var right = -Float.MAX_VALUE; var bottom = -Float.MAX_VALUE
                listOf(logo, appName, tagline, glowRing, loadingPct).forEach { v ->
                    v.getLocationOnScreen(loc)
                    left   = minOf(left,   loc[0].toFloat())
                    top    = minOf(top,    loc[1].toFloat())
                    right  = maxOf(right,  (loc[0] + v.width).toFloat())
                    bottom = maxOf(bottom, (loc[1] + v.height).toFloat())
                }
                if (left < right && top < bottom) {
                    particleView.setContentExclusionZone(RectF(left, top, right, bottom))
                }
            }
        }, 900)

        // ══════════════════════════════════════════════════════
        // PLAY ALL
        // ══════════════════════════════════════════════════════
        AnimatorSet().apply {
            playTogether(
                // Beat 1-2: Logo entrance + bloom flash
                logoEntrance, bloomFlash,
                // Beat 2 sustained: glow breathe
                glowPulseAlpha, glowPulseScaleX, glowPulseScaleY,
                // Beat 3: logo alive
                logoFloat, logoWobble, logoPulseX, logoPulseY,
                // Beat 4: app name reveal
                appNameSlideY, appNameAlpha, appNameScaleX, appNameScaleY,
                typewriterAnimator, appNameGlow,
                // Beat 5: tagline
                taglineSlideY, taglineAlpha,
                // Beat 6: ring
                ringAlpha, ringScaleX, ringScaleY, ringPulseX, ringPulseY,
                // Beat 7: loading + progress
                loadingAlpha, progressAnimator,
                // Beat 8: copyright
                copyrightAlpha
            )
            start()
        }
        loadingDots.start()

        // ══════════════════════════════════════════════════════
        // NAVIGATE — fade out partikel 620ms sebelum transisi
        // ══════════════════════════════════════════════════════
        Handler(Looper.getMainLooper()).postDelayed({
            particleView.fadeOutAndStop()
            animatedBg.stopAnimation()
            glowRing.stopAnimation()
            Handler(Looper.getMainLooper()).postDelayed({
                val destination = if (AppLaunchPreferences.isOnboardingCompleted(this)) {
                    MainActivity::class.java
                } else {
                    OnboardingActivity::class.java
                }
                startActivity(Intent(this, destination))
                overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
                finish()
            }, 620)
        }, SPLASH_DURATION - 620)
    }
}
