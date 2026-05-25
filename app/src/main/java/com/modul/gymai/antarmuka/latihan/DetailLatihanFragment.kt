package com.modul.gymai.antarmuka.latihan

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayoutMediator
import com.modul.gymai.R
import com.modul.gymai.databinding.FragmentDetailLatihanBinding
import com.modul.gymai.ui.MaterialSymbols

class DetailLatihanFragment : Fragment() {

    companion object {
        const val ARG_EXERCISE_ID   = "exercise_id"
        const val ARG_EXERCISE_TYPE = "exerciseType"

        /** SharedPreferences key untuk mode pose detector. */
        const val PREFS_NAME        = "gymai_settings"
        const val PREF_DETECTOR_MODE = "pose_detector_mode"
        const val MODE_ML_KIT       = "ML_KIT"
        const val MODE_YOLO         = "YOLO"
    }

    private var _binding: FragmentDetailLatihanBinding? = null
    private val binding get() = _binding!!

    private lateinit var exerciseId: String
    private lateinit var exercise: Exercise
    private var detailPageChangeCallback: ViewPager2.OnPageChangeCallback? = null

    /** Mode pose detector yang dipilih sebelum memulai deteksi. */
    private var selectedMode: String = MODE_ML_KIT

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentDetailLatihanBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        MaterialSymbols.applyToTree(binding.root)
        applySystemBarInsets()

        exerciseId = arguments?.getString(ARG_EXERCISE_ID) ?: "1"
        exercise = ExerciseRepository.getAllExercises().find { it.id == exerciseId }
            ?: ExerciseRepository.getAllExercises().first()

        setupHeader()
        setupInfoCard()
        setupTabs()
        setupDetectorModeToggle()

        binding.btnBack.setOnClickListener {
            findNavController().navigateUp()
        }

        binding.fabStartDetection.setOnClickListener {
            // Simpan mode yang dipilih ke SharedPreferences sebelum navigate
            requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(PREF_DETECTOR_MODE, selectedMode)
                .apply()

            val args = Bundle().apply {
                putString(ARG_EXERCISE_TYPE, exercise.type.name)
            }
            findNavController().navigate(R.id.deteksiFragment, args)
        }
    }

    private fun setupHeader() {
        binding.ivExerciseImage.setImageResource(exercise.imageResId)
        binding.tvExerciseName.text = exercise.name
        binding.tvExerciseDesc.text = exercise.description

        val (badgeBg, badgeText) = when (exercise.level) {
            "Pemula"    -> Pair("#E8F5E9", "#22C55E")
            "Menengah"  -> Pair("#FFF3E0", "#F59E0B")
            "Lanjutan"  -> Pair("#FFEBEE", "#C1121F")
            else        -> Pair("#FFF3E0", "#F59E0B")
        }
        binding.tvLevelBadge.text = exercise.level
        binding.tvLevelBadge.setTextColor(Color.parseColor(badgeText))
    }

    /**
     * Mode toggle dinonaktifkan — default selalu ML Kit.
     * Fungsi ini tetap ada untuk menjaga kompatibilitas binding,
     * tapi tidak mengekspos UI toggle ke user.
     */
    private fun setupDetectorModeToggle() {
        // Paksa ML Kit sebagai satu-satunya mode
        selectedMode = MODE_ML_KIT

        // Simpan ke SharedPreferences agar sesi sebelumnya yang mungkin menyimpan YOLO
        // di-override kembali ke ML_KIT
        requireContext()
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_DETECTOR_MODE, MODE_ML_KIT)
            .apply()

        // Toggle dinonaktifkan — view sudah GONE di XML, listener sengaja tidak dipasang
        updateDetectorModeLabel()
    }

    private fun updateDetectorModeLabel() {
        // Selalu tampilkan ML Kit (view GONE di XML, tapi label tetap diset untuk konsistensi)
        binding.tvDetectorModeLabel.text = "✨ ML Kit"
    }

    private fun setupInfoCard() {
        binding.tvInfoMuscle.text = exercise.primaryMuscleGroup
        binding.tvInfoLevel.text = exercise.level
        binding.tvInfoEquipment.text = exercise.equipment
    }

    private fun setupTabs() {
        val tabTitles = listOf("Panduan", "Teknik", "Otot")

        val adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount() = 3
            override fun createFragment(position: Int): Fragment {
                return when (position) {
                    0 -> PanduanTabFragment.newInstance(exerciseId)
                    1 -> TeknikTabFragment.newInstance(exerciseId)
                    else -> OtotTabFragment.newInstance(exerciseId)
                }
            }
        }

        binding.viewPagerDetail.adapter = adapter
        binding.viewPagerDetail.isUserInputEnabled = true

        TabLayoutMediator(binding.tabLayout, binding.viewPagerDetail) { tab, position ->
            tab.text = tabTitles[position]
        }.attach()

        detailPageChangeCallback = object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                binding.viewPagerDetail.post { updateViewPagerHeight() }
            }
        }.also { callback ->
            binding.viewPagerDetail.registerOnPageChangeCallback(callback)
        }
        binding.viewPagerDetail.post { updateViewPagerHeight() }
        binding.viewPagerDetail.postDelayed({
            if (_binding != null) updateViewPagerHeight()
        }, 120L)
    }

    private fun updateViewPagerHeight() {
        val recyclerView = binding.viewPagerDetail.getChildAt(0) as? RecyclerView ?: return
        val currentView = recyclerView.layoutManager
            ?.findViewByPosition(binding.viewPagerDetail.currentItem)
            ?: return

        val widthSpec = View.MeasureSpec.makeMeasureSpec(
            binding.viewPagerDetail.width.coerceAtLeast(1),
            View.MeasureSpec.EXACTLY
        )
        val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        currentView.measure(widthSpec, heightSpec)

        val minHeight = resources.getDimensionPixelSize(R.dimen.detail_min_tab_height)
        val targetHeight = currentView.measuredHeight.coerceAtLeast(minHeight)
        binding.viewPagerDetail.updateLayoutParams<ViewGroup.LayoutParams> {
            height = targetHeight
        }
    }

    private fun applySystemBarInsets() {
        val initialFabBottomMargin =
            (binding.fabStartDetection.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin
        val initialBackTopMargin =
            (binding.btnBack.layoutParams as ViewGroup.MarginLayoutParams).topMargin
        val initialScrollBottomPadding = binding.nestedScroll.paddingBottom

        binding.nestedScroll.clipToPadding = false
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val displayCutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            val bottomInset = maxOf(systemBars.bottom, displayCutout.bottom)
            val topInset = maxOf(systemBars.top, displayCutout.top)

            binding.fabStartDetection.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = initialFabBottomMargin + bottomInset
            }
            binding.nestedScroll.setPadding(
                binding.nestedScroll.paddingLeft,
                binding.nestedScroll.paddingTop,
                binding.nestedScroll.paddingRight,
                initialScrollBottomPadding + bottomInset
            )
            binding.btnBack.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = initialBackTopMargin + topInset
            }

            insets
        }
        ViewCompat.requestApplyInsets(binding.root)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        detailPageChangeCallback?.let { binding.viewPagerDetail.unregisterOnPageChangeCallback(it) }
        detailPageChangeCallback = null
        _binding = null
    }
}
