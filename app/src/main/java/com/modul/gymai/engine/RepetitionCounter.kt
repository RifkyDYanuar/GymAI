package com.modul.gymai.engine

/**
 * Exercise-aware repetition counter using an UP ↔ DOWN state machine.
 */
class RepetitionCounter(private val exerciseType: ExerciseType = ExerciseType.SQUAT) {

    enum class PhaseState { UP, DOWN }

    companion object {
        // Thresholds
        private const val SQUAT_DOWN_THRESHOLD = 130f
        private const val SQUAT_UP_THRESHOLD   = 165f

        private const val CURL_DOWN_THRESHOLD = 65f
        private const val CURL_UP_THRESHOLD   = 150f

        private const val RAISE_UP_THRESHOLD   = 70f
        private const val RAISE_DOWN_THRESHOLD = 30f

        private const val PRESS_UP_THRESHOLD   = 155f
        private const val PRESS_DOWN_THRESHOLD = 110f
    }

    private var currentState: PhaseState = PhaseState.UP
    private var repCount: Int = 0
    private var lastValue: Float = 0f

    fun onNewFrame(value: Float, isFormValid: Boolean = true) {
        lastValue = value
        when (exerciseType) {
            ExerciseType.SQUAT -> updateSquat(value)
            ExerciseType.BICEP_CURL -> updateBicepCurlPhase(value)
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

    private fun updateBicepCurlPhase(angle: Float) {
        when (currentState) {
            PhaseState.UP -> if (angle < CURL_DOWN_THRESHOLD) {
                currentState = PhaseState.DOWN
            }
            PhaseState.DOWN -> if (angle > CURL_UP_THRESHOLD) currentState = PhaseState.UP
        }
    }

    fun onBicepRepCompleted(shouldCountRep: Boolean) {
        if (exerciseType == ExerciseType.BICEP_CURL && shouldCountRep) {
            repCount++
        }
    }

    private fun updateLateralRaise(angle: Float) {
        when (currentState) {
            PhaseState.DOWN -> if (angle > RAISE_UP_THRESHOLD) currentState = PhaseState.UP
            PhaseState.UP -> if (angle < RAISE_DOWN_THRESHOLD) {
                currentState = PhaseState.DOWN
                repCount++
            }
        }
    }

    private fun updateShoulderPress(angle: Float) {
        when (currentState) {
            PhaseState.DOWN -> if (angle > PRESS_UP_THRESHOLD) currentState = PhaseState.UP
            PhaseState.UP -> if (angle < PRESS_DOWN_THRESHOLD) {
                currentState = PhaseState.DOWN
                repCount++
            }
        }
    }

    fun getRepCount(): Int = repCount
    fun reset() {
        repCount = 0
        currentState = when (exerciseType) {
            ExerciseType.LATERAL_RAISE, ExerciseType.SHOULDER_PRESS -> PhaseState.DOWN
            else -> PhaseState.UP
        }
        lastValue = 0f
    }
}
