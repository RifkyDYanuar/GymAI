package com.modul.gymai.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Represents a single workout session recorded by the user.
 */
@Entity(tableName = "workout_sessions")
data class WorkoutSession(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val totalReps: Int = 0,
    val averageConfidence: Float = 0f,
    val durationSeconds: Long = 0,
    val exerciseType: String = "SQUAT",
    val mostFrequentFeedback: String? = null,
    val feedbackSummary: String? = null,
    val evaluationVideoPath: String? = null
)
