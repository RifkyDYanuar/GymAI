package com.modul.gymai.antarmuka.latihan

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.navigation.fragment.findNavController
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.google.android.material.tabs.TabLayoutMediator
import com.modul.gymai.R
import com.modul.gymai.databinding.FragmentDetailLatihanBinding

class DetailLatihanFragment : Fragment() {

    companion object {
        const val ARG_EXERCISE_ID = "exercise_id"
        const val ARG_EXERCISE_TYPE = "exerciseType"
    }

    private var _binding: FragmentDetailLatihanBinding? = null
    private val binding get() = _binding!!

    private lateinit var exerciseId: String
    private lateinit var exercise: Exercise

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentDetailLatihanBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        exerciseId = arguments?.getString(ARG_EXERCISE_ID) ?: "1"
        exercise = ExerciseRepository.getAllExercises().find { it.id == exerciseId }
            ?: ExerciseRepository.getAllExercises().first()

        setupHeader()
        setupInfoCard()
        setupTabs()

        binding.btnBack.setOnClickListener {
            findNavController().navigateUp()
        }

        binding.fabStartDetection.setOnClickListener {
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

        // Level badge color
        val (badgeBg, badgeText) = when (exercise.level) {
            "Pemula" -> Pair("#E8F5E9", "#22C55E")
            "Menengah" -> Pair("#FFF3E0", "#F59E0B")
            "Lanjutan" -> Pair("#FFEBEE", "#C1121F")
            else -> Pair("#FFF3E0", "#F59E0B")
        }
        binding.tvLevelBadge.text = exercise.level
        binding.tvLevelBadge.setTextColor(Color.parseColor(badgeText))
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
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
