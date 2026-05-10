package com.modul.gymai.antarmuka.beranda

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.modul.gymai.data.GymDatabase
import com.modul.gymai.data.WorkoutRepository
import com.modul.gymai.data.WorkoutSession
import com.modul.gymai.engine.ExerciseType
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

class BerandaViewModel(private val repository: WorkoutRepository) : ViewModel() {

    data class BestEvaluationUiModel(
        val title: String,
        val headline: String,
        val supporting: String,
        val footer: String,
        val isEmpty: Boolean
    )

    data class TrainingImprovementUiModel(
        val title: String,
        val headline: String,
        val supporting: String,
        val footer: String,
        val isPositive: Boolean,
        val isNeutral: Boolean,
        val isEmpty: Boolean
    )

    private val _todayReps = MutableLiveData(0)
    val todayReps: LiveData<Int> = _todayReps

    private val _totalSessions = MutableLiveData(0)
    val totalSessions: LiveData<Int> = _totalSessions

    private val _recentSessions = MutableLiveData<List<WorkoutSession>>(emptyList())
    val recentSessions: LiveData<List<WorkoutSession>> = _recentSessions

    private val _bestEvaluation = MutableLiveData(
        BestEvaluationUiModel(
            title = "Evaluasi Terbaik",
            headline = "Belum ada evaluasi",
            supporting = "Selesaikan sesi latihan untuk melihat hasil terbaik.",
            footer = "Akurasi dan repetisi terbaik akan tampil di sini",
            isEmpty = true
        )
    )
    val bestEvaluation: LiveData<BestEvaluationUiModel> = _bestEvaluation

    private val _trainingImprovement = MutableLiveData(
        TrainingImprovementUiModel(
            title = "Saran Latihan Terbaru",
            headline = "Belum ada evaluasi terbaru",
            supporting = "Selesaikan sesi latihan untuk melihat feedback terakhir.",
            footer = "Feedback terbaru akan tampil di sini",
            isPositive = true,
            isNeutral = true,
            isEmpty = true
        )
    )
    val trainingImprovement: LiveData<TrainingImprovementUiModel> = _trainingImprovement

    init {
        loadStats()
    }

    private fun loadStats() {
        viewModelScope.launch {
            val allSessions = repository.getAllSessionsSnapshot()
            _todayReps.value = repository.getTodayReps()
            _totalSessions.value = repository.getTotalSessions()
            _recentSessions.value = repository.getRecentSessions(5)
            _bestEvaluation.value = buildBestEvaluation(allSessions)
            _trainingImprovement.value = buildTrainingImprovement(allSessions)
        }
    }

    fun refresh() {
        loadStats()
    }

    private fun buildBestEvaluation(sessions: List<WorkoutSession>): BestEvaluationUiModel {
        val bestSession = sessions.maxWithOrNull(
            compareBy<WorkoutSession> { it.averageConfidence }
                .thenBy { it.totalReps }
                .thenBy { it.timestamp }
        ) ?: return BestEvaluationUiModel(
            title = "Evaluasi Terbaik",
            headline = "Belum ada evaluasi",
            supporting = "Selesaikan sesi latihan untuk melihat hasil terbaik.",
            footer = "Akurasi dan repetisi terbaik akan tampil di sini",
            isEmpty = true
        )

        val confidencePercent = (bestSession.averageConfidence * 100f).roundToInt()
        return BestEvaluationUiModel(
            title = "Evaluasi Terbaik",
            headline = "${formatExerciseName(bestSession.exerciseType)} ${confidencePercent}%",
            supporting = "${bestSession.totalReps} repetisi terbaik",
            footer = formatShortDate(bestSession.timestamp),
            isEmpty = false
        )
    }

    private fun buildTrainingImprovement(sessions: List<WorkoutSession>): TrainingImprovementUiModel {
        if (sessions.isEmpty()) {
            return TrainingImprovementUiModel(
                title = "Saran Latihan Terbaru",
                headline = "Belum ada evaluasi terbaru",
                supporting = "Selesaikan sesi latihan untuk melihat feedback terakhir.",
                footer = "Feedback terbaru akan tampil di sini",
                isPositive = true,
                isNeutral = true,
                isEmpty = true
            )
        }

        val latestSession = sessions.maxByOrNull { it.timestamp } ?: sessions.last()
        val latestFeedback = latestSession.mostFrequentFeedback
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        val latestSummary = latestSession.feedbackSummary
            ?.lineSequence()
            ?.map { it.trim() }
            ?.firstOrNull { line -> line.isNotEmpty() && !line.startsWith("-") }
            ?: latestSession.feedbackSummary?.trim().takeIf { !it.isNullOrEmpty() }

        val headline = latestFeedback ?: "Pertahankan kualitas gerakan"
        val supporting = latestSummary ?: "Fokus pada tempo stabil dan postur yang konsisten."
        val footer = "${formatExerciseName(latestSession.exerciseType)} - ${formatShortDate(latestSession.timestamp)}"

        return TrainingImprovementUiModel(
            title = "Saran Latihan Terbaru",
            headline = headline,
            supporting = supporting,
            footer = footer,
            isPositive = true,
            isNeutral = false,
            isEmpty = false
        )
    }

    private fun formatExerciseName(type: String): String {
        return ExerciseType.fromString(type).displayName
    }

    private fun formatShortDate(timestamp: Long): String {
        return SimpleDateFormat("dd MMM yyyy", Locale("id", "ID")).format(Date(timestamp))
    }
}

class BerandaViewModelFactory(private val context: Context) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        val db = GymDatabase.getInstance(context)
        val repo = WorkoutRepository(db.workoutSessionDao(), context.applicationContext)
        @Suppress("UNCHECKED_CAST")
        return BerandaViewModel(repo) as T
    }
}
