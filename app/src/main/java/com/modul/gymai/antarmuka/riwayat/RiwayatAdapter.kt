package com.modul.gymai.antarmuka.riwayat

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.modul.gymai.R
import com.modul.gymai.data.WorkoutSession
import com.modul.gymai.databinding.ItemRiwayatBinding
import com.modul.gymai.engine.ExerciseType
import com.modul.gymai.ui.MaterialSymbols
import com.modul.gymai.utils.EvaluationVideoStorage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class RiwayatAdapter : ListAdapter<WorkoutSession, RiwayatAdapter.HistoryViewHolder>(DiffCallback()) {

    companion object {
        private val previewExecutor = Executors.newFixedThreadPool(2)
    }

    var onDeleteClick: ((WorkoutSession) -> Unit)? = null
    var onItemClick: ((WorkoutSession) -> Unit)? = null
    
    private val dateFormat = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale("id", "ID"))

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HistoryViewHolder {
        val binding = ItemRiwayatBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        MaterialSymbols.applyToTree(binding.root)
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
            val iconSymbol = when (session.exerciseType) {
                ExerciseType.SQUAT.name -> "directions_walk"
                ExerciseType.BICEP_CURL.name -> "fitness_center"
                ExerciseType.LATERAL_RAISE.name -> "accessibility_new"
                ExerciseType.SHOULDER_PRESS.name -> "sports_gymnastics"
                else -> "fitness_center"
            }
            MaterialSymbols.applyImageView(binding.ivExerciseIcon, iconSymbol)

            val previewPlaceholderRes = when (session.exerciseType) {
                ExerciseType.SQUAT.name -> R.drawable.squat
                ExerciseType.BICEP_CURL.name -> R.drawable.bicepcurl
                ExerciseType.LATERAL_RAISE.name -> R.drawable.lateralraise
                ExerciseType.SHOULDER_PRESS.name -> R.drawable.shoulderpress
                else -> R.drawable.logo
            }

            // Feedback Section
            val feedbackText = when {
                !session.feedbackSummary.isNullOrBlank() -> session.feedbackSummary
                !session.mostFrequentFeedback.isNullOrBlank() -> "Feedback utama:\n- ${session.mostFrequentFeedback}"
                else -> null
            }

            if (!feedbackText.isNullOrEmpty()) {
                binding.layoutFeedback.visibility = View.VISIBLE
                binding.tvFeedbackHistory.text = feedbackText
            } else {
                binding.layoutFeedback.visibility = View.GONE
            }

            val playableVideo = EvaluationVideoStorage.resolvePlayableVideo(context, session.evaluationVideoPath)
            if (playableVideo != null) {
                binding.tvVideoAvailability.visibility = View.VISIBLE
                binding.tvVideoAvailability.text = "Video evaluasi tersedia"
                binding.layoutVideoPreview.visibility = View.VISIBLE
                bindVideoPreview(
                    imageView = binding.ivVideoPreview,
                    playableVideo = playableVideo,
                    previewKey = session.evaluationVideoPath ?: "session-${session.id}",
                    placeholderRes = previewPlaceholderRes
                )
            } else {
                binding.tvVideoAvailability.visibility = View.GONE
                binding.layoutVideoPreview.visibility = View.GONE
                binding.ivVideoPreview.setImageDrawable(null)
            }


            // Delete click
            binding.root.setOnClickListener {
                onItemClick?.invoke(session)
            }
            binding.btnDeleteHistory.setOnClickListener {
                onDeleteClick?.invoke(session)
            }
        }

        private fun bindVideoPreview(
            imageView: ImageView,
            playableVideo: java.io.File,
            previewKey: String,
            placeholderRes: Int
        ) {
            imageView.tag = previewKey
            imageView.setImageResource(placeholderRes)

            previewExecutor.execute {
                val previewBitmap = EvaluationVideoStorage.getPreviewFrame(playableVideo)
                imageView.post {
                    if (imageView.tag != previewKey) return@post

                    if (previewBitmap != null) {
                        imageView.setImageBitmap(previewBitmap)
                    } else {
                        imageView.setImageResource(placeholderRes)
                    }
                }
            }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<WorkoutSession>() {
        override fun areItemsTheSame(old: WorkoutSession, new: WorkoutSession) = old.id == new.id
        override fun areContentsTheSame(old: WorkoutSession, new: WorkoutSession) = old == new
    }
}
