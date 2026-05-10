package com.modul.gymai.antarmuka.beranda

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup as VG
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.modul.gymai.AppLaunchPreferences
import com.modul.gymai.R
import com.modul.gymai.antarmuka.latihan.ExerciseRepository
import com.modul.gymai.databinding.FragmentBerandaBinding
import com.modul.gymai.ui.GuideOverlayView
import com.modul.gymai.ui.MaterialSymbols
import android.animation.ValueAnimator
import android.os.Handler
import android.os.Looper
import android.graphics.Color
import java.text.SimpleDateFormat
import java.util.*

class BerandaFragment : Fragment() {

    private var _binding: FragmentBerandaBinding? = null
    private val binding get() = _binding!!

    private val viewModel: BerandaViewModel by viewModels {
        BerandaViewModelFactory(requireContext())
    }

    private lateinit var recentActivityAdapter: RecentActivityAdapter
    private lateinit var exerciseInfoAdapter: ExerciseInfoAdapter

    // ── Typewriter greeting ──
    private val greetingMessages = listOf(
        "Halo, Selamat Berlatih!",
        "Ayo, Capai Target Hari Ini!",
        "Konsisten adalah Kunci!",
        "Gerak Cerdas, Tubuh Sehat!",
        "Semangat! Kamu Bisa!"
    )
    private var greetingIndex = 0
    private var typewriterPhase = 0  // 0=typing, 1=pause, 2=erasing
    private var typewriterCharCount = 0
    private val typewriterHandler = Handler(Looper.getMainLooper())
    private var typewriterRunnable: Runnable? = null
    private var cursorVisible = true
    private val cursorHandler = Handler(Looper.getMainLooper())
    private var cursorRunnable: Runnable? = null

    private val clockHandler = Handler(Looper.getMainLooper())
    private val clockRunnable = object : Runnable {
        override fun run() {
            updateClock()
            clockHandler.postDelayed(this, 1000)
        }
    }

    // ── Guide overlay ──
    private var guideShown = false
    private val guideHandler = Handler(Looper.getMainLooper())

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentBerandaBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        MaterialSymbols.applyToTree(binding.root)
        setupDate()
        setupRecyclerViews()
        setupClickListeners()
        observeViewModel()
        // startCursorBlink dipanggil di onResume agar aman dengan lifecycle
    }

    private fun setupDate() {
        val sdfDate = SimpleDateFormat("EEEE, dd MMM yyyy", Locale("id", "ID"))
        binding.tvDate.text = sdfDate.format(Date())
        updateClock()
    }

    private fun updateClock() {
        val sdfTime = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        binding.tvRealtimeClock.text = sdfTime.format(Date())
    }

    private fun setupRecyclerViews() {
        // 1. Exercise Info (Horizontal)
        val exercises = ExerciseRepository.getAllExercises()
        exerciseInfoAdapter = ExerciseInfoAdapter(exercises) { exercise ->
            val args = Bundle().apply {
                putString("exercise_id", exercise.id)
            }
            findNavController().navigate(R.id.detailLatihanFragment, args)
        }
        binding.rvExerciseInfo.apply {
            layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
            adapter = exerciseInfoAdapter
        }

        // 2. Recent Activity (Vertical)
        recentActivityAdapter = RecentActivityAdapter()
        binding.rvRecentActivity.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = recentActivityAdapter
        }
    }

    private fun setupClickListeners() {
        binding.btnStartDetection.setOnClickListener {
            findNavController().navigate(R.id.latihanFragment)
        }

        binding.btnSeeAllExercises.setOnClickListener {
            findNavController().navigate(R.id.latihanFragment)
        }
    }

    // ── Typewriter animation ──

    private fun startCursorBlink() {
        cursorRunnable?.let { cursorHandler.removeCallbacks(it) }
        cursorRunnable = object : Runnable {
            override fun run() {
                cursorVisible = !cursorVisible
                renderGreetingText()
                cursorHandler.postDelayed(this, 400)
            }
        }
        cursorHandler.post(cursorRunnable!!)
    }

    private fun startGreetingTypewriter() {
        typewriterRunnable?.let { typewriterHandler.removeCallbacks(it) }
        typewriterPhase = 0
        typewriterCharCount = 0
        scheduleNextTypewriterStep()
    }

    private fun scheduleNextTypewriterStep() {
        val currentMsg = greetingMessages[greetingIndex]
        typewriterRunnable = Runnable {
            when (typewriterPhase) {
                // Phase 0: mengetik karakter satu per satu
                0 -> {
                    if (typewriterCharCount < currentMsg.length) {
                        typewriterCharCount++
                        renderGreetingText()
                        scheduleNextTypewriterStep()
                    } else {
                        // Selesai mengetik → masuk jeda
                        typewriterPhase = 1
                        scheduleNextTypewriterStep()
                    }
                }
                // Phase 1: jeda setelah teks penuh
                1 -> {
                    typewriterPhase = 2
                    scheduleNextTypewriterStep()
                }
                // Phase 2: hapus karakter mundur
                2 -> {
                    if (typewriterCharCount > 0) {
                        typewriterCharCount--
                        renderGreetingText()
                        scheduleNextTypewriterStep()
                    } else {
                        // Ganti ke pesan berikutnya
                        greetingIndex = (greetingIndex + 1) % greetingMessages.size
                        typewriterPhase = 0
                        scheduleNextTypewriterStep()
                    }
                }
            }
        }
        val delay: Long = when (typewriterPhase) {
            0 -> if (typewriterCharCount == 0) 300L else 55L   // mulai: jeda kecil, lalu ketik
            1 -> 2000L                                           // jeda baca 2 detik
            2 -> 28L                                            // hapus lebih cepat dari ketik
            else -> 55L
        }
        typewriterHandler.postDelayed(typewriterRunnable!!, delay)
    }

    private fun renderGreetingText() {
        val b = _binding ?: return   // guard: jangan crash jika view sudah destroyed
        val currentMsg = greetingMessages[greetingIndex]
        val displayed = currentMsg.substring(0, typewriterCharCount)
        val cursor = if (cursorVisible) "|" else " "
        b.tvGreeting.text = "$displayed$cursor"
    }

    private fun stopGreetingTypewriter() {
        typewriterRunnable?.let { typewriterHandler.removeCallbacks(it) }
        cursorRunnable?.let { cursorHandler.removeCallbacks(it) }
    }

    // ── Home Guide (first-time only) ────────────────────────────────────

    private fun startHomeGuideIfNeeded() {
        if (guideShown) return
        if (AppLaunchPreferences.isHomeGuideShown(requireContext())) return
        guideShown = true

        // Tunda sedikit agar layout selesai dirender
        guideHandler.postDelayed({
            val ctx = context ?: return@postDelayed
            val b   = _binding  ?: return@postDelayed

            val steps = buildGuideSteps(b)
            if (steps.isEmpty()) return@postDelayed

            val guide = GuideOverlayView(ctx)
            guide.setSteps(steps)
            guide.setOnFinishListener {
                AppLaunchPreferences.setHomeGuideShown(requireContext())
            }

            // Tambahkan ke root window (decorView) agar menutupi seluruh layar
            val decorView = requireActivity().window.decorView as VG
            val lp = VG.LayoutParams(
                VG.LayoutParams.MATCH_PARENT,
                VG.LayoutParams.MATCH_PARENT
            )
            decorView.addView(guide, lp)
            guide.start()
        }, 600)
    }

    private fun buildGuideSteps(b: FragmentBerandaBinding): List<GuideOverlayView.GuideStep> {
        val sv = b.nestedScrollView
        val steps = mutableListOf<GuideOverlayView.GuideStep>()

        fun scrollTo(target: View): () -> Unit = {
            val loc = IntArray(2)
            target.getLocationInWindow(loc)
            val svLoc = IntArray(2)
            sv.getLocationInWindow(svLoc)
            val targetTopInSv = loc[1] - svLoc[1] + sv.scrollY
            val scrollTarget = (targetTopInSv - 120).coerceAtLeast(0)
            sv.smoothScrollTo(0, scrollTarget)
        }

        // Step 1: Mulai Latihan — tooltip di TENGAH layar
        steps.add(
            GuideOverlayView.GuideStep(
                targetView          = b.btnStartDetection,
                title               = "Mulai Latihan",
                description         = "Ketuk di sini untuk memilih gerakan dan memulai sesi evaluasi pose secara real-time menggunakan kamera.",
                highlightPadding    = 16,
                tooltipVerticalBias = 0.42f   // tengah
            )
        )

        // Step 2: Total Repetisi — tooltip TENGAH AGAK BAWAH
        steps.add(
            GuideOverlayView.GuideStep(
                targetView          = b.cardStats,
                title               = "Total Repetisi Hari Ini",
                description         = "Angka ini menunjukkan total repetisi yang telah kamu lakukan hari ini dari semua sesi latihan.",
                highlightPadding    = 12,
                tooltipVerticalBias = 0.58f,  // tengah agak bawah
                onBeforeShow        = { sv.smoothScrollTo(0, 0) }
            )
        )

        // Step 3: Evaluasi Terbaik — fokus hanya di judul card
        steps.add(
            GuideOverlayView.GuideStep(
                targetView          = b.tvBestEvaluationTitle,
                title               = "Evaluasi Terbaik",
                description         = "Menampilkan gerakan terbaikmu berdasarkan skor akurasi tertinggi dari semua sesi latihan yang pernah dilakukan.",
                highlightPadding    = 6,   // kecil agar spotlight pas di judul saja
                tooltipVerticalBias = 0.28f,
                onBeforeShow        = scrollTo(b.tvBestEvaluationTitle)
            )
        )

        // Step 4: Saran Latihan — fokus hanya di judul card
        steps.add(
            GuideOverlayView.GuideStep(
                targetView          = b.tvImprovementTitle,
                title               = "Saran Latihan Terbaru",
                description         = "Berisi feedback otomatis dari sesi terakhirmu — apakah performa meningkat, stabil, atau perlu diperbaiki.",
                highlightPadding    = 6,   // kecil agar spotlight pas di judul saja
                tooltipVerticalBias = 0.20f,
                onBeforeShow        = scrollTo(b.tvImprovementTitle)
            )
        )

        // Step 5: Jelajahi Gerakan — tooltip TENGAH
        steps.add(
            GuideOverlayView.GuideStep(
                targetView          = b.sectionJelajahi,
                title               = "Jelajahi Gerakan",
                description         = "Geser ke kanan untuk melihat semua gerakan yang tersedia. Ketuk kartu gerakan untuk melihat panduan detail.",
                highlightPadding    = 12,
                isScrollHint        = true,
                tooltipVerticalBias = 0.38f,  // tengah
                onBeforeShow        = scrollTo(b.sectionJelajahi)
            )
        )

        // Step 6: Aktivitas Terakhir — tooltip TENGAH
        steps.add(
            GuideOverlayView.GuideStep(
                targetView          = b.tvAktivitasTerakhirHeader,
                title               = "Aktivitas Terakhir",
                description         = "Riwayat sesi latihanmu yang paling baru ditampilkan di sini. Gulir ke bawah untuk melihat semua aktivitas.",
                highlightPadding    = 16,
                tooltipVerticalBias = 0.42f,  // tengah
                onBeforeShow        = scrollTo(b.tvAktivitasTerakhirHeader)
            )
        )

        // Step 7: FAB Evaluasi — arahkan ke tombol FAB tengah di bottom navigation
        // FAB ada di DashboardFragment (parent), akses lewat activity root view
        val fabEvaluasi = requireActivity().findViewById<View>(R.id.fab_evaluasi)
        if (fabEvaluasi != null) {
            steps.add(
                GuideOverlayView.GuideStep(
                    targetView          = fabEvaluasi,
                    title               = "🎯 Tombol Evaluasi Pose",
                    description         = "Ketuk tombol ini untuk langsung masuk ke menu Evaluasi! Pilih gerakan (Bicep Curl, Squat, Lateral Raise, dll), lalu kamera terbuka dan GymPose AI mengevaluasi teknik gerakanmu secara real-time.",
                    highlightPadding    = 20,
                    tooltipVerticalBias = 0.72f,  // di atas FAB (FAB ada di bawah layar)
                    onBeforeShow        = { sv.smoothScrollTo(0, 0) }
                )
            )
        }

        return steps
    }


    private fun observeViewModel() {
        viewModel.todayReps.observe(viewLifecycleOwner) { reps ->
            binding.tvTodayReps.text = reps.toString()
        }

        viewModel.bestEvaluation.observe(viewLifecycleOwner) { bestEvaluation ->
            binding.tvBestEvaluationTitle.text = bestEvaluation.title
            binding.tvBestEvaluationHeadline.text = bestEvaluation.headline
            binding.tvBestEvaluationSupporting.text = bestEvaluation.supporting
            binding.tvBestEvaluationFooter.text = bestEvaluation.footer
        }

        viewModel.trainingImprovement.observe(viewLifecycleOwner) { improvement ->
            binding.tvImprovementTitle.text = improvement.title
            binding.tvImprovementHeadline.text = improvement.headline
            binding.tvImprovementSupporting.text = improvement.supporting
            binding.tvImprovementFooter.text = improvement.footer

            val accentColor = when {
                improvement.isPositive -> Color.parseColor("#C1121F")
                improvement.isNeutral -> Color.parseColor("#B45309")
                else -> Color.parseColor("#991B1B")
            }
            binding.tvImprovementHeadline.setTextColor(accentColor)
            binding.tvImprovementSupporting.setTextColor(accentColor)
        }

        viewModel.recentSessions.observe(viewLifecycleOwner) { sessions ->
            recentActivityAdapter.submitList(sessions)
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refresh()
        clockHandler.post(clockRunnable)
        startCursorBlink()          // aman karena binding sudah ada di onResume
        startGreetingTypewriter()
        startHomeGuideIfNeeded()
    }

    override fun onPause() {
        super.onPause()
        clockHandler.removeCallbacks(clockRunnable)
        stopGreetingTypewriter()
        guideHandler.removeCallbacksAndMessages(null)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // Hentikan SEMUA handler sebelum binding di-null-kan
        // untuk mencegah NullPointerException saat callback terpanggil
        clockHandler.removeCallbacksAndMessages(null)
        typewriterHandler.removeCallbacksAndMessages(null)
        cursorHandler.removeCallbacksAndMessages(null)
        guideHandler.removeCallbacksAndMessages(null)
        _binding = null
    }
}
