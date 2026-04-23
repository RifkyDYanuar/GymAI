package com.modul.gymai.antarmuka.latihan

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.modul.gymai.R
import com.modul.gymai.databinding.FragmentTabPanduanBinding
import com.modul.gymai.ui.MaterialSymbols

class PanduanTabFragment : Fragment() {

    companion object {
        private const val ARG_EXERCISE_ID = "exercise_id"
        fun newInstance(exerciseId: String): PanduanTabFragment {
            return PanduanTabFragment().apply {
                arguments = Bundle().apply { putString(ARG_EXERCISE_ID, exerciseId) }
            }
        }
    }

    private var _binding: FragmentTabPanduanBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentTabPanduanBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        MaterialSymbols.applyToTree(binding.root)
        val exerciseId = arguments?.getString(ARG_EXERCISE_ID) ?: return
        val detail = ExerciseDetailRepository.getDetail(exerciseId) ?: return

        binding.tvDefinition.text = detail.definition
        binding.tvStepsHeader.text = "Langkah-langkah (${detail.steps.size} step)"

        binding.rvSteps.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = StepAdapter(detail.steps)
            isNestedScrollingEnabled = false
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
