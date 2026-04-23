package com.modul.gymai.antarmuka.latihan

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.modul.gymai.R
import com.modul.gymai.databinding.FragmentTabOtotBinding
import com.modul.gymai.ui.MaterialSymbols

class OtotTabFragment : Fragment() {

    companion object {
        private const val ARG_EXERCISE_ID = "exercise_id"
        fun newInstance(exerciseId: String): OtotTabFragment {
            return OtotTabFragment().apply {
                arguments = Bundle().apply { putString(ARG_EXERCISE_ID, exerciseId) }
            }
        }
    }

    private var _binding: FragmentTabOtotBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentTabOtotBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        MaterialSymbols.applyToTree(binding.root)
        val exerciseId = arguments?.getString(ARG_EXERCISE_ID) ?: return
        val detail = ExerciseDetailRepository.getDetail(exerciseId) ?: return

        binding.tvPrimaryMuscle.text = detail.primaryMuscle
        binding.tvPrimaryMuscleDesc.text = detail.primaryMuscleDesc
        binding.tvImportance.text = detail.importanceDesc

        binding.rvMuscleGroups.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = MuscleGroupAdapter(detail.muscleGroups)
            isNestedScrollingEnabled = false
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
