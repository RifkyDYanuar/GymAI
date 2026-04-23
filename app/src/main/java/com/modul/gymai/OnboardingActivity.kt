package com.modul.gymai

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.viewpager2.widget.ViewPager2
import com.modul.gymai.ui.MaterialSymbols

class OnboardingActivity : AppCompatActivity() {

    private lateinit var viewPager: ViewPager2
    private lateinit var llDots: LinearLayout
    private lateinit var tvGetStarted: TextView

    private val AUTO_SCROLL_DELAY = 3000L  // 3 detik per slide
    private var currentPage = 0
    private val autoScrollHandler = Handler(Looper.getMainLooper())

    private val slides = listOf(
        OnboardingSlide(
            backgroundRes = R.drawable.onboarding1,
            title = "GymAI",
            subtitle = "Latih lebih cerdas dengan sistem\ndeteksi pose berbasis AI"
        ),
        OnboardingSlide(
            backgroundRes = R.drawable.onboarding2,
            title = "Deteksi Pose\nReal-Time",
            subtitle = "Pantau setiap gerakan Anda secara\nlangsung dengan presisi tinggi"
        ),
        OnboardingSlide(
            backgroundRes = R.drawable.onboarding3,
            title = "Perbaiki Teknik\nLatihan",
            subtitle = "Dapatkan feedback akurat untuk mencegah\ncedera saat berolahraga"
        )
    )

    private val autoScrollRunnable = object : Runnable {
        override fun run() {
            if (currentPage < slides.size - 1) {
                currentPage++
                viewPager.setCurrentItem(currentPage, true)
                autoScrollHandler.postDelayed(this, AUTO_SCROLL_DELAY)
            }
            // Stop auto-scroll at last page — user must tap "Get Started"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()

        // Buat status bar transparan (Edge-to-Edge)
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)

        setContentView(R.layout.activity_onboarding)
        MaterialSymbols.applyToTree(findViewById(android.R.id.content))

        viewPager = findViewById(R.id.viewpager_onboarding)
        llDots = findViewById(R.id.ll_dots)
        tvGetStarted = findViewById(R.id.tv_get_started)

        val adapter = OnboardingAdapter(slides)
        viewPager.adapter = adapter
        viewPager.setPageTransformer(ZoomOutPageTransformer())

        setupDotIndicators()
        updateDots(0)

        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                currentPage = position
                updateDots(position)

                if (position == slides.size - 1) {
                    // Last slide: show Get Started, stop auto scroll
                    tvGetStarted.visibility = View.VISIBLE
                    autoScrollHandler.removeCallbacks(autoScrollRunnable)
                } else {
                    tvGetStarted.visibility = View.INVISIBLE
                }
            }
        })

        tvGetStarted.setOnClickListener {
            goToMain()
        }

        // Start auto-scroll
        autoScrollHandler.postDelayed(autoScrollRunnable, AUTO_SCROLL_DELAY)
    }

    private fun setupDotIndicators() {
        llDots.removeAllViews()
        slides.forEachIndexed { index, _ ->
            val dot = ImageView(this)
            dot.setImageDrawable(
                ContextCompat.getDrawable(this, R.drawable.dot_inactive)
            )
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = 8
            }
            dot.layoutParams = params
            llDots.addView(dot)
        }
    }

    private fun updateDots(activeIndex: Int) {
        for (i in 0 until llDots.childCount) {
            val dot = llDots.getChildAt(i) as ImageView
            if (i == activeIndex) {
                dot.setImageDrawable(ContextCompat.getDrawable(this, R.drawable.dot_active))
                // Active dot is wider (pill shape defined in dot_active.xml)
                val params = dot.layoutParams as LinearLayout.LayoutParams
                params.width = resources.getDimensionPixelSize(R.dimen.dot_active_width)
                params.height = resources.getDimensionPixelSize(R.dimen.dot_height)
                dot.layoutParams = params
            } else {
                dot.setImageDrawable(ContextCompat.getDrawable(this, R.drawable.dot_inactive))
                val params = dot.layoutParams as LinearLayout.LayoutParams
                params.width = resources.getDimensionPixelSize(R.dimen.dot_height)
                params.height = resources.getDimensionPixelSize(R.dimen.dot_height)
                dot.layoutParams = params
            }
        }
    }

    private fun goToMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        autoScrollHandler.removeCallbacks(autoScrollRunnable)
    }
}

class ZoomOutPageTransformer : ViewPager2.PageTransformer {
    private val MIN_SCALE = 0.85f
    private val MIN_ALPHA = 0.5f

    override fun transformPage(view: View, position: Float) {
        view.apply {
            val pageWidth = width
            val pageHeight = height
            when {
                position < -1 -> { // [-Infinity,-1)
                    // Halaman sepenuhnya di luar layar ke kiri.
                    alpha = 0f
                }
                position <= 1 -> { // [-1,1]
                    // Ubah transisi slide default untuk memperkecil/memperbesar halaman
                    val scaleFactor = Math.max(MIN_SCALE, 1 - Math.abs(position))
                    val vertMargin = pageHeight * (1 - scaleFactor) / 2
                    val horzMargin = pageWidth * (1 - scaleFactor) / 2
                    translationX = if (position < 0) {
                        horzMargin - vertMargin / 2
                    } else {
                        horzMargin + vertMargin / 2
                    }

                    // Skala perkecilan halaman (antara MIN_SCALE dan 1)
                    scaleX = scaleFactor
                    scaleY = scaleFactor

                    // Pudarkan halaman relatif terhadap ukurannya.
                    alpha = (MIN_ALPHA +
                            (((scaleFactor - MIN_SCALE) / (1 - MIN_SCALE)) * (1 - MIN_ALPHA)))
                }
                else -> { // (1,+Infinity]
                    // Halaman sepenuhnya di luar layar ke kanan.
                    alpha = 0f
                }
            }
        }
    }
}
