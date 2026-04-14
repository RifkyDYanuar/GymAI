package com.modul.gymai.antarmuka.latihan

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.modul.gymai.databinding.FragmentTabTeknikBinding

class TeknikTabFragment : Fragment() {

    companion object {
        private const val ARG_EXERCISE_ID = "exercise_id"
        fun newInstance(exerciseId: String): TeknikTabFragment {
            return TeknikTabFragment().apply {
                arguments = Bundle().apply { putString(ARG_EXERCISE_ID, exerciseId) }
            }
        }
    }

    private var _binding: FragmentTabTeknikBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentTabTeknikBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val exerciseId = arguments?.getString(ARG_EXERCISE_ID) ?: return
        val detail = ExerciseDetailRepository.getDetail(exerciseId) ?: return

        binding.rvCorrect.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = TechniqueAdapter(detail.correctTechniques, isCorrect = true)
            isNestedScrollingEnabled = false
        }

        binding.rvWrong.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = TechniqueAdapter(detail.wrongTechniques, isCorrect = false)
            isNestedScrollingEnabled = false
        }

        binding.rvTips.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = TipAdapter(detail.tips)
            isNestedScrollingEnabled = false
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
