package com.modul.gymai.data

import android.content.Context
import androidx.lifecycle.LiveData
import com.modul.gymai.utils.EvaluationVideoStorage

/**
 * Repository pattern: single source of truth for workout data.
 */
class WorkoutRepository(
    private val dao: WorkoutSessionDao,
    private val appContext: Context? = null
) {

    val allSessions: LiveData<List<WorkoutSession>> = dao.getAllSessions()

    fun getSessionsByType(type: String): LiveData<List<WorkoutSession>> {
        return dao.getSessionsByType(type)
    }

    fun getSessionById(id: Long): LiveData<WorkoutSession?> {
        return dao.getSessionById(id)
    }

    suspend fun insertSession(session: WorkoutSession): Long {
        return dao.insertSession(session)
    }

    suspend fun deleteSession(session: WorkoutSession) {
        appContext?.let { context ->
            EvaluationVideoStorage.deleteVideoArtifacts(context, session.evaluationVideoPath)
        }
        dao.deleteSession(session)
    }

    suspend fun getTodayReps(): Int {
        val todayStart = getTodayStartTimestamp()
        return dao.getTotalRepsSince(todayStart) ?: 0
    }

    suspend fun getTotalSessions(): Int {
        return dao.getSessionCount()
    }

    suspend fun getAverageAccuracy(): Float {
        return dao.getAverageAccuracy() ?: 0f
    }

    suspend fun getTotalRepsAllTime(): Int {
        return dao.getTotalRepsAllTime() ?: 0
    }

    suspend fun getRecentSessions(limit: Int): List<WorkoutSession> {
        return dao.getRecentSessions(limit)
    }

    private fun getTodayStartTimestamp(): Long {
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }
}
