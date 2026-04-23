package com.modul.gymai.antarmuka.latihan

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.modul.gymai.R
import com.modul.gymai.databinding.FragmentLatihanBinding
import com.modul.gymai.engine.ExerciseType
import com.modul.gymai.ui.MaterialSymbols

class LatihanFragment : Fragment() {

    private var _binding: FragmentLatihanBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLatihanBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        MaterialSymbols.applyToTree(binding.root)

        val exercises = ExerciseRepository.getAllExercises()

        val adapter = LatihanAdapter(
            exercises = exercises,
            onDetailClick = { exercise ->
                navigateToDetail(exercise.id)
            },
            onDetectClick = { exercise ->
                navigateToDetection(exercise.type)
            }
        )

        binding.rvExercises.apply {
            layoutManager = LinearLayoutManager(requireContext())
            this.adapter = adapter
        }
    }

    private fun navigateToDetail(exerciseId: String) {
        val args = Bundle().apply {
            putString("exercise_id", exerciseId)
        }
        try {
            findNavController().navigate(R.id.action_latihan_to_detail, args)
        } catch (e: Exception) {
            requireParentFragment().findNavController().navigate(R.id.action_latihan_to_detail, args)
        }
    }

    private fun navigateToDetection(exerciseType: ExerciseType) {
        val args = Bundle().apply {
            putString("exerciseType", exerciseType.name)
        }
        try {
            findNavController().navigate(R.id.action_exercise_to_detection, args)
        } catch (e: Exception) {
            requireParentFragment().findNavController().navigate(R.id.action_exercise_to_detection, args)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
