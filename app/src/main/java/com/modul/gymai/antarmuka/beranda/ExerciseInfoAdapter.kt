package com.modul.gymai.antarmuka.beranda

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.modul.gymai.antarmuka.latihan.Exercise
import com.modul.gymai.databinding.ItemExerciseInfoHomeBinding

class ExerciseInfoAdapter(
    private val exercises: List<Exercise>,
    private val onItemClick: (Exercise) -> Unit
) : RecyclerView.Adapter<ExerciseInfoAdapter.ViewHolder>() {

    inner class ViewHolder(private val binding: ItemExerciseInfoHomeBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(exercise: Exercise) {
            binding.tvExerciseNameHome.text = exercise.name
            binding.tvExerciseTargetHome.text = exercise.primaryMuscleGroup
            binding.tvExerciseLevelHome.text = exercise.level
            binding.ivExerciseHome.setImageResource(exercise.imageResId)
            
            binding.root.setOnClickListener { onItemClick(exercise) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemExerciseInfoHomeBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(exercises[position])
    }

    override fun getItemCount(): Int = exercises.size
}
