package com.modul.gymai.antarmuka.latihan

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.modul.gymai.R
import com.modul.gymai.ui.MaterialSymbols

class LatihanAdapter(
    private val exercises: List<Exercise>,
    private val onDetailClick: (Exercise) -> Unit,
    private val onDetectClick: (Exercise) -> Unit
) : RecyclerView.Adapter<LatihanAdapter.ExerciseViewHolder>() {

    inner class ExerciseViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val ivExerciseImage: ImageView = itemView.findViewById(R.id.ivExerciseImage)
        val tvExerciseNumber: TextView = itemView.findViewById(R.id.tvExerciseNumber)
        val tvExerciseLevel: TextView = itemView.findViewById(R.id.tvExerciseLevel)
        val tvExerciseName: TextView = itemView.findViewById(R.id.tvExerciseName)
        val tvExerciseDesc: TextView = itemView.findViewById(R.id.tvExerciseDesc)

        val tvMuscleGroup: TextView = itemView.findViewById(R.id.tvMuscleGroup)
        val tvEquipment: TextView = itemView.findViewById(R.id.tvEquipment)

        val tvMuscle1: TextView = itemView.findViewById(R.id.tvMuscle1)
        val tvMuscle2: TextView = itemView.findViewById(R.id.tvMuscle2)
        val tvMuscle3: TextView = itemView.findViewById(R.id.tvMuscle3)
        val tvMuscleMore: TextView = itemView.findViewById(R.id.tvMuscleMore)

        val tvStepsTitle: TextView = itemView.findViewById(R.id.tvStepsTitle)
        val tvFirstStepBrief: TextView = itemView.findViewById(R.id.tvFirstStepBrief)

        val btnDetail: Button = itemView.findViewById(R.id.btnDetail)
        val btnDetect: Button = itemView.findViewById(R.id.btnDetect)

        fun bind(exercise: Exercise, position: Int) {
            tvExerciseNumber.text = "${position + 1}"
            tvExerciseLevel.text = exercise.level
            tvExerciseName.text = exercise.name
            tvExerciseDesc.text = exercise.description

            tvMuscleGroup.text = exercise.primaryMuscleGroup
            tvEquipment.text = exercise.equipment

            val muscles = exercise.muscleTags
            tvMuscle1.text = muscles.getOrNull(0) ?: ""
            tvMuscle1.visibility = if (muscles.isNotEmpty()) View.VISIBLE else View.GONE

            tvMuscle2.text = muscles.getOrNull(1) ?: ""
            tvMuscle2.visibility = if (muscles.size > 1) View.VISIBLE else View.GONE

            tvMuscle3.text = muscles.getOrNull(2) ?: ""
            tvMuscle3.visibility = if (muscles.size > 2) View.VISIBLE else View.GONE

            tvMuscleMore.text = muscles.getOrNull(3) ?: ""
            tvMuscleMore.visibility = if (muscles.size > 3) View.VISIBLE else View.GONE

            tvStepsTitle.text = "${exercise.stepCount} Langkah Gerakan"
            tvFirstStepBrief.text = exercise.firstStepBrief

            ivExerciseImage.setImageResource(exercise.imageResId)

            if (exercise.level == "Menengah") {
                tvExerciseLevel.setTextColor(android.graphics.Color.parseColor("#374151"))
                tvExerciseLevel.setBackgroundResource(R.drawable.bg_chip_yellow)
            } else {
                tvExerciseLevel.setTextColor(itemView.context.getColor(R.color.success))
                tvExerciseLevel.setBackgroundResource(R.drawable.bg_chip_benar)
            }

            btnDetect.setOnClickListener {
                onDetectClick(exercise)
            }

            btnDetail.setOnClickListener {
                onDetailClick(exercise)
            }

            itemView.setOnClickListener {
                onDetailClick(exercise)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ExerciseViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_latihan, parent, false)
        MaterialSymbols.applyToTree(view)
        return ExerciseViewHolder(view)
    }

    override fun onBindViewHolder(holder: ExerciseViewHolder, position: Int) {
        holder.bind(exercises[position], position)
    }

    override fun getItemCount(): Int = exercises.size
}
