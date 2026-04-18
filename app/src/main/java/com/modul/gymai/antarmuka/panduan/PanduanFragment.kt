package com.modul.gymai.antarmuka.panduan

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.modul.gymai.R
import com.modul.gymai.antarmuka.latihan.Exercise
import com.modul.gymai.antarmuka.latihan.ExerciseDetailRepository
import com.modul.gymai.antarmuka.latihan.ExerciseRepository
import com.modul.gymai.databinding.FragmentPanduanBinding

class PanduanFragment : Fragment() {

    private var _binding: FragmentPanduanBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPanduanBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val exercises = ExerciseRepository.getAllExercises()

        // Map card IDs to exercise IDs
        val cardMap = mapOf(
            binding.cardSquat to exercises.find { it.id == "1" },
            binding.cardBicepCurl to exercises.find { it.id == "2" },
            binding.cardLateralRaise to exercises.find { it.id == "3" },
            binding.cardShoulderPress to exercises.find { it.id == "4" }
        )

        cardMap.forEach { (card, exercise) ->
            exercise?.let {
                card.setOnClickListener {
                    showExerciseModal(exercise)
                }
            }
        }
    }

    private fun showExerciseModal(exercise: Exercise) {
        val detail = ExerciseDetailRepository.getDetail(exercise.id) ?: return

        val dialog = BottomSheetDialog(requireContext(), R.style.BottomSheetDialogTheme)
        val sheetView = LayoutInflater.from(requireContext())
            .inflate(R.layout.bottom_sheet_panduan, null)

        dialog.setContentView(sheetView)

        // Set expanded state
        dialog.setOnShowListener {
            val bottomSheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            bottomSheet?.let {
                val behavior = BottomSheetBehavior.from(it)
                // Set height to 90% of screen
                val displayMetrics = resources.displayMetrics
                it.layoutParams.height = (displayMetrics.heightPixels * 0.90).toInt()
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true
            }
        }

        // Bind data
        sheetView.findViewById<ImageView>(R.id.iv_modal_image).setImageResource(exercise.imageResId)
        sheetView.findViewById<TextView>(R.id.tv_modal_name).text = exercise.name
        sheetView.findViewById<TextView>(R.id.tv_modal_muscle).text = exercise.primaryMuscleGroup
        sheetView.findViewById<TextView>(R.id.tv_modal_level).text = exercise.level
        sheetView.findViewById<TextView>(R.id.tv_modal_steps_count).text = detail.steps.size.toString()
        sheetView.findViewById<TextView>(R.id.tv_modal_equipment).text = exercise.equipment
        sheetView.findViewById<TextView>(R.id.tv_modal_definition).text = detail.definition

        // Steps
        val llSteps = sheetView.findViewById<LinearLayout>(R.id.ll_modal_steps)
        detail.steps.forEachIndexed { index, step ->
            llSteps.addView(createStepItem(index + 1, step))
        }

        // Correct Techniques
        val llCorrect = sheetView.findViewById<LinearLayout>(R.id.ll_modal_correct)
        detail.correctTechniques.forEach { tech ->
            llCorrect.addView(createBulletItem(tech, isCorrect = true))
        }

        // Wrong Techniques
        val llWrong = sheetView.findViewById<LinearLayout>(R.id.ll_modal_wrong)
        detail.wrongTechniques.forEach { tech ->
            llWrong.addView(createBulletItem(tech, isCorrect = false))
        }

        // Tips
        val llTips = sheetView.findViewById<LinearLayout>(R.id.ll_modal_tips)
        detail.tips.forEach { tip ->
            llTips.addView(createTipItem(tip))
        }

        // Close button
        sheetView.findViewById<ImageView>(R.id.btn_modal_close).setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun createStepItem(number: Int, text: String): View {
        val inflater = LayoutInflater.from(requireContext())
        val item = inflater.inflate(R.layout.item_panduan_step, null)
        item.findViewById<TextView>(R.id.tv_step_number).text = number.toString()
        item.findViewById<TextView>(R.id.tv_step_text).text = text
        return item
    }

    private fun createBulletItem(text: String, isCorrect: Boolean): View {
        val inflater = LayoutInflater.from(requireContext())
        val item = inflater.inflate(R.layout.item_panduan_bullet, null)
        val tvBullet = item.findViewById<TextView>(R.id.tv_bullet_icon)
        val tvText = item.findViewById<TextView>(R.id.tv_bullet_text)
        tvBullet.text = if (isCorrect) "✓" else "✗"
        tvBullet.setTextColor(
            if (isCorrect)
                resources.getColor(R.color.success, null)
            else
                resources.getColor(R.color.primary, null)
        )
        tvText.text = text
        return item
    }

    private fun createTipItem(text: String): View {
        val inflater = LayoutInflater.from(requireContext())
        val item = inflater.inflate(R.layout.item_panduan_tip, null)
        item.findViewById<TextView>(R.id.tv_tip_text).text = text
        return item
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
