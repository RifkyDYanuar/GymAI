package com.modul.gymai.processing

import com.modul.gymai.pose.PoseResult

/**
 * Exercise-aware repetition counter using an UP ↔ DOWN state machine.
 * 
 * Simplified to receive the calculated biometric value (angle or level) 
 * directly from the detection pipeline.
 */
class RepetitionCounter(private val exerciseType: ExerciseType = ExerciseType.SQUAT) {

    enum class PhaseState { UP, DOWN }

    companion object {
        // Thresholds consistent with Rule Engines
        
        // SQUAT (Knee Angle): DOWN < 130 (easier trigger), UP > 165
        private const val SQUAT_DOWN_THRESHOLD = 130f
        private const val SQUAT_UP_THRESHOLD   = 165f

        // BICEP_CURL (Elbow Angle): DOWN < 65 (Strict peak), UP > 155
        private const val CURL_DOWN_THRESHOLD = 65f
        private const val CURL_UP_THRESHOLD   = 150f

        // LATERAL_RAISE (Shoulder Angle): UP > 70, DOWN < 30
        private const val RAISE_UP_THRESHOLD   = 70f
        private const val RAISE_DOWN_THRESHOLD = 30f

        // SHOULDER_PRESS (Avg Elbow Angle): UP > 150, DOWN < 110
        private const val PRESS_UP_THRESHOLD   = 155f
        private const val PRESS_DOWN_THRESHOLD = 110f
    }

    private var currentState: PhaseState = PhaseState.UP
    private var repCount: Int = 0
    private var lastValue: Float = 0f

    /**
     * Process a new frame with its calculated biomechanical value.
     */
    fun onNewFrame(pose: PoseResult, value: Float) {
        if (!pose.isValid()) return
        
        lastValue = value

        when (exerciseType) {
            ExerciseType.SQUAT -> updateSquat(value)
            ExerciseType.BICEP_CURL -> updateBicepCurl(value)
            ExerciseType.LATERAL_RAISE -> updateLateralRaise(value)
            ExerciseType.SHOULDER_PRESS -> updateShoulderPress(value)
        }
    }

    private fun updateSquat(angle: Float) {
        when (currentState) {
            PhaseState.UP -> if (angle < SQUAT_DOWN_THRESHOLD) currentState = PhaseState.DOWN
            PhaseState.DOWN -> if (angle > SQUAT_UP_THRESHOLD) {
                currentState = PhaseState.UP
                repCount++
            }
        }
    }

    private fun updateBicepCurl(angle: Float) {
        when (currentState) {
            PhaseState.UP -> if (angle < CURL_DOWN_THRESHOLD) currentState = PhaseState.DOWN
            PhaseState.DOWN -> if (angle > CURL_UP_THRESHOLD) {
                currentState = PhaseState.UP
                repCount++
            }
        }
    }

    private fun updateLateralRaise(angle: Float) {
        // UP = arm raised high
        when (currentState) {
            PhaseState.DOWN -> if (angle > RAISE_UP_THRESHOLD) currentState = PhaseState.UP
            PhaseState.UP -> if (angle < RAISE_DOWN_THRESHOLD) {
                currentState = PhaseState.DOWN
                repCount++
            }
        }
    }

    private fun updateShoulderPress(angle: Float) {
        // UP = arms pushed high (large angle)
        // DOWN = arms at starting pos (small angle ~90)
        when (currentState) {
            PhaseState.DOWN -> if (angle > PRESS_UP_THRESHOLD) currentState = PhaseState.UP
            PhaseState.UP -> if (angle < PRESS_DOWN_THRESHOLD) {
                currentState = PhaseState.DOWN
                repCount++
            }
        }
    }

    fun getRepCount(): Int = repCount
    fun getCurrentState(): PhaseState = currentState
    fun getLastValue(): Float = lastValue

    fun reset() {
        repCount = 0
        // Initial state depends on exercise type (rest position)
        currentState = when (exerciseType) {
            ExerciseType.LATERAL_RAISE -> PhaseState.DOWN
            ExerciseType.SHOULDER_PRESS -> PhaseState.DOWN
            else -> PhaseState.UP
        }
        lastValue = 0f
    }
}
