package com.modul.gymai.engine

import com.modul.gymai.pose.PoseResult

/**
 * Standard interface for all exercise validation logic.
 */
interface ExerciseRuleEngine {
    /**
     * Authenticate if the current pose adheres to the exercise rules.
     */
    fun validate(pose: PoseResult?): RuleResult
    
    /**
     * Calculate the specific biomechanical metric (angle, level) used for repetition counting.
     */
    fun calculateMetric(pose: PoseResult): Float
    
    /**
     * Reset any state within the engine.
     */
    fun reset()
}

/**
 * Common result object for all rule engines.
 */
enum class BicepRepStatus {
    IDLE,
    IN_PROGRESS,
    REP_GOOD,
    REP_BAD
}

data class RuleResult(
    val isValid: Boolean,
    val feedback: String,
    val primaryMetric: Float = 0f,
    val secondaryMetric: Float = 0f,
    val torsoAngle: Float = 0f,
    val liveFeedback: String = feedback,
    val repStatus: BicepRepStatus = BicepRepStatus.IDLE,
    val repCompleted: Boolean = false,
    val shouldCountRep: Boolean = false
)
