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
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.absoluteValue
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
            title = "Peningkatan Latihan",
            headline = "Belum cukup data",
            supporting = "Butuh minimal dua periode latihan untuk membaca progres.",
            footer = "7 hari terakhir dibanding 7 hari sebelumnya",
            isPositive = false,
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
                title = "Peningkatan Latihan",
                headline = "Belum cukup data",
                supporting = "Butuh minimal dua periode latihan untuk membaca progres.",
                footer = "7 hari terakhir dibanding 7 hari sebelumnya",
                isPositive = false,
                isNeutral = true,
                isEmpty = true
            )
        }

        val now = System.currentTimeMillis()
        val sevenDaysMs = 7L * 24L * 60L * 60L * 1000L
        val currentStart = now - sevenDaysMs
        val previousStart = now - (sevenDaysMs * 2L)

        val currentWeekReps = sessions
            .filter { it.timestamp in currentStart..now }
            .sumOf { it.totalReps }
        val previousWeekReps = sessions
            .filter { it.timestamp in previousStart until currentStart }
            .sumOf { it.totalReps }

        if (previousWeekReps == 0) {
            return TrainingImprovementUiModel(
                title = "Peningkatan Latihan",
                headline = "Belum cukup data",
                supporting = "Perlu data dari minggu sebelumnya untuk membaca peningkatan.",
                footer = "7 hari terakhir dibanding 7 hari sebelumnya",
                isPositive = false,
                isNeutral = true,
                isEmpty = true
            )
        }

        val delta = currentWeekReps - previousWeekReps
        val percentage = ((delta.toFloat() / previousWeekReps.toFloat()) * 100f).roundToInt()

        val status = when {
            delta.absoluteValue <= 1 -> "Stabil"
            delta > 0 -> "Meningkat"
            else -> "Menurun"
        }

        val deltaPrefix = when {
            delta > 0 -> "+"
            delta < 0 -> "-"
            else -> ""
        }

        return TrainingImprovementUiModel(
            title = "Peningkatan Latihan",
            headline = status,
            supporting = "$deltaPrefix${delta.absoluteValue} repetisi (${deltaPrefix}${percentage.absoluteValue}%)",
            footer = "7 hari terakhir dibanding 7 hari sebelumnya",
            isPositive = delta > 1,
            isNeutral = delta.absoluteValue <= 1,
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
