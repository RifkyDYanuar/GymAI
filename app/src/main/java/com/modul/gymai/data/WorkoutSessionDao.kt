package com.modul.gymai.data

import androidx.lifecycle.LiveData
import androidx.room.*

@Dao
interface WorkoutSessionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: WorkoutSession): Long

    @Query("SELECT * FROM workout_sessions ORDER BY timestamp DESC")
    fun getAllSessions(): LiveData<List<WorkoutSession>>

    @Query("SELECT * FROM workout_sessions WHERE exerciseType = :type ORDER BY timestamp DESC")
    fun getSessionsByType(type: String): LiveData<List<WorkoutSession>>

    @Query("SELECT * FROM workout_sessions WHERE id = :id LIMIT 1")
    fun getSessionById(id: Long): LiveData<WorkoutSession?>

    @Query("SELECT * FROM workout_sessions ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentSessions(limit: Int): List<WorkoutSession>

    @Query("SELECT SUM(totalReps) FROM workout_sessions WHERE timestamp > :sinceTimestamp")
    suspend fun getTotalRepsSince(sinceTimestamp: Long): Int?

    @Query("SELECT AVG(averageConfidence) FROM workout_sessions")
    suspend fun getAverageAccuracy(): Float?

    @Query("SELECT COUNT(*) FROM workout_sessions")
    suspend fun getSessionCount(): Int

    @Query("SELECT SUM(totalReps) FROM workout_sessions")
    suspend fun getTotalRepsAllTime(): Int?

    @Delete
    suspend fun deleteSession(session: WorkoutSession)

    @Query("DELETE FROM workout_sessions")
    suspend fun deleteAll()
}
