package com.modul.gymai.antarmuka.latihan

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.modul.gymai.databinding.ItemTechniqueBinding
import com.modul.gymai.databinding.FragmentTabTeknikBinding
import com.modul.gymai.ui.MaterialSymbols

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
        MaterialSymbols.applyToTree(binding.root)
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

        binding.rvTips.removeAllViews()
        detail.tips.forEach { tip ->
            val itemBinding = ItemTechniqueBinding.inflate(layoutInflater, binding.rvTips, false)
            itemBinding.tvTechnique.text = tip
            MaterialSymbols.applyImageView(itemBinding.ivIcon, "lightbulb")
            itemBinding.ivIcon.imageTintList = android.content.res.ColorStateList.valueOf(
                android.graphics.Color.parseColor("#F59E0B")
            )
            itemBinding.ivIcon.scaleType = ImageView.ScaleType.CENTER
            itemBinding.root.setBackgroundResource(android.R.color.transparent)
            binding.rvTips.addView(itemBinding.root)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
