package com.modul.gymai.antarmuka.riwayat

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.modul.gymai.R
import com.modul.gymai.data.WorkoutSession
import com.modul.gymai.databinding.ItemRiwayatBinding
import com.modul.gymai.processing.ExerciseType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RiwayatAdapter : ListAdapter<WorkoutSession, RiwayatAdapter.HistoryViewHolder>(DiffCallback()) {

    var onDeleteClick: ((WorkoutSession) -> Unit)? = null
    
    private val dateFormat = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale("id", "ID"))

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HistoryViewHolder {
        val binding = ItemRiwayatBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return HistoryViewHolder(binding)
    }

    override fun onBindViewHolder(holder: HistoryViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class HistoryViewHolder(private val binding: ItemRiwayatBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(session: WorkoutSession) {
            val context = binding.root.context
            
            // Basic Info
            binding.tvExerciseTypeName.text = ExerciseType.fromString(session.exerciseType).displayName
            binding.tvSessionDate.text = dateFormat.format(Date(session.timestamp))
            binding.tvSessionReps.text = "${session.totalReps} Repetisi"
            
            // Duration formatting
            val minutes = session.durationSeconds / 60
            val seconds = session.durationSeconds % 60
            binding.tvSessionDuration.text = String.format("%02d:%02d", minutes, seconds)

            
            // Icon mapping
            val iconRes = when (session.exerciseType) {
                ExerciseType.SQUAT.name -> R.drawable.ic_steps
                ExerciseType.BICEP_CURL.name -> R.drawable.ic_muscle
                ExerciseType.LATERAL_RAISE.name -> R.drawable.ic_bolt
                ExerciseType.SHOULDER_PRESS.name -> R.drawable.ic_fitness_center
                else -> R.drawable.ic_fitness_center
            }
            binding.ivExerciseIcon.setImageResource(iconRes)

            // Feedback Section
            if (!session.mostFrequentFeedback.isNullOrEmpty()) {
                binding.layoutFeedback.visibility = View.VISIBLE
                binding.tvFeedbackHistory.text = "Saran: ${session.mostFrequentFeedback}"
            } else {
                binding.layoutFeedback.visibility = View.GONE
            }


            // Delete click
            binding.btnDeleteHistory.setOnClickListener {
                onDeleteClick?.invoke(session)
            }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<WorkoutSession>() {
        override fun areItemsTheSame(old: WorkoutSession, new: WorkoutSession) = old.id == new.id
        override fun areContentsTheSame(old: WorkoutSession, new: WorkoutSession) = old == new
    }
}
