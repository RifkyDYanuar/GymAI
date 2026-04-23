package com.modul.gymai.antarmuka.beranda

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.modul.gymai.data.WorkoutSession
import com.modul.gymai.databinding.ItemRecentActivityHomeBinding
import com.modul.gymai.ui.MaterialSymbols
import java.text.SimpleDateFormat
import java.util.*

class RecentActivityAdapter : ListAdapter<WorkoutSession, RecentActivityAdapter.ViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemRecentActivityHomeBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        MaterialSymbols.applyToTree(binding.root)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class ViewHolder(private val binding: ItemRecentActivityHomeBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(session: WorkoutSession) {
            binding.tvRecentExerciseName.text = formatExerciseName(session.exerciseType)
            binding.tvRecentExerciseReps.text = "${session.totalReps} Reps"
            
            
            binding.tvRecentExerciseTime.text = getRelativeTime(session.timestamp)

            // Dynamic icon based on type
            val iconSymbol = when (session.exerciseType.uppercase()) {
                "SQUAT" -> "directions_walk"
                "PUSHUP" -> "bolt"
                "BICEP_CURL" -> "fitness_center"
                "LUNGES" -> "stairs"
                else -> "bolt"
            }
            MaterialSymbols.applyImageView(binding.ivRecentExerciseIcon, iconSymbol)
        }

        private fun formatExerciseName(type: String): String {
            return type.lowercase()
                .replace("_", " ")
                .split(" ")
                .joinToString(" ") { it.replaceFirstChar { char -> char.uppercase() } }
        }

        private fun getRelativeTime(timestamp: Long): String {
            val now = System.currentTimeMillis()
            val diff = now - timestamp
            
            return when {
                diff < 60000 -> "Baru saja"
                diff < 3600000 -> "${diff / 60000} menit yang lalu"
                diff < 86400000 -> "${diff / 3600000} jam yang lalu"
                else -> SimpleDateFormat("dd MMM", Locale("id", "ID")).format(Date(timestamp))
            }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<WorkoutSession>() {
        override fun areItemsTheSame(old: WorkoutSession, new: WorkoutSession) = old.id == new.id
        override fun areContentsTheSame(old: WorkoutSession, new: WorkoutSession) = old == new
    }
}
