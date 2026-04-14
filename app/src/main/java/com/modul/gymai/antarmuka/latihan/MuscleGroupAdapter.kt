package com.modul.gymai.antarmuka.latihan

import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.modul.gymai.R
import com.modul.gymai.databinding.ItemMuscleGroupBinding

class MuscleGroupAdapter(private val muscles: List<MuscleGroup>) :
    RecyclerView.Adapter<MuscleGroupAdapter.ViewHolder>() {

    inner class ViewHolder(val binding: ItemMuscleGroupBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemMuscleGroupBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val muscle = muscles[position]
        with(holder.binding) {
            tvMuscleName.text = muscle.name
            when (muscle.type) {
                MuscleType.PRIMARY -> {
                    vMuscleDot.setBackgroundResource(R.drawable.bg_muscle_dot_primary)
                    tvMuscleType.text = "Primer"
                    tvMuscleType.setBackgroundResource(R.drawable.bg_muscle_type_badge)
                    tvMuscleType.setTextColor(Color.WHITE)
                }
                MuscleType.SECONDARY -> {
                    vMuscleDot.setBackgroundResource(R.drawable.bg_muscle_dot_secondary)
                    tvMuscleType.text = "Sekunder"
                    tvMuscleType.setBackgroundResource(R.drawable.bg_muscle_type_secondary)
                    tvMuscleType.setTextColor(Color.WHITE)
                }
                MuscleType.TERTIARY -> {
                    vMuscleDot.setBackgroundResource(R.drawable.bg_muscle_dot_tertiary)
                    tvMuscleType.text = "Stabilizer"
                    tvMuscleType.setBackgroundResource(R.drawable.bg_muscle_type_tertiary)
                    tvMuscleType.setTextColor(Color.parseColor("#6C757D"))
                }
            }
        }
    }

    override fun getItemCount() = muscles.size
}
