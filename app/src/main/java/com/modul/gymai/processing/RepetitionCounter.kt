package com.modul.gymai.processing

import com.modul.gymai.pose.PoseResult

/**
 * Exercise-aware repetition counter menggunakan state machine UP ↔ DOWN.
 *
 * Setiap jenis gerakan memiliki threshold angle yang berbeda:
 *
 *   SQUAT:
 *     sudut lutut < 100° → DOWN, > 150° → UP
 *
 *   BICEP_CURL:
 *     sudut siku < 60° → DOWN (kontraksi penuh), > 140° → UP (lengan lurus)
 *
 *   LATERAL_RAISE:
 *     level abduksi > 0.75 → UP (lengan sejajar bahu), < 0.25 → DOWN
 *
 *   SHOULDER_PRESS:
 *     rata-rata sudut siku > 160° → UP (lengan lurus ke atas), < 110° → DOWN
 */
class RepetitionCounter(private val exerciseType: ExerciseType = ExerciseType.SQUAT) {

    enum class PhaseState { UP, DOWN }

    companion object {
        // SQUAT thresholds (sudut lutut)
        private const val SQUAT_DOWN_THRESHOLD = 100f
        private const val SQUAT_UP_THRESHOLD   = 150f

        // BICEP_CURL thresholds (sudut siku)
        private const val CURL_DOWN_THRESHOLD = 65f   // siku tertekuk = DOWN
        private const val CURL_UP_THRESHOLD   = 140f  // lengan hampir lurus = UP

        // LATERAL_RAISE thresholds (abduksi level 0.0–1.0)
        private const val RAISE_UP_THRESHOLD   = 0.70f  // lengan mencapai ketinggian bahu
        private const val RAISE_DOWN_THRESHOLD = 0.25f  // lengan kembali ke bawah

        // SHOULDER_PRESS thresholds (sudut siku rata-rata)
        private const val PRESS_UP_THRESHOLD   = 155f   // lengan lurus ke atas
        private const val PRESS_DOWN_THRESHOLD = 110f   // siku kembali ~90°
    }

    // Rule engines — inisialisasi lazy sesuai exercise type
    private val squatEngine    by lazy { SquatRuleEngine() }
    private val curlEngine     by lazy { BicepCurlRuleEngine() }
    private val raiseEngine    by lazy { LateralRaiseRuleEngine() }
    private val pressEngine    by lazy { ShoulderPressRuleEngine() }

    private var currentState: PhaseState = PhaseState.UP
    private var repCount: Int = 0
    private var lastValue: Float = 0f   // sudut atau level tergantung exercise

    /**
     * Update state machine dengan pose terbaru.
     * @return true jika satu repetisi baru selesai.
     */
    fun update(pose: PoseResult?): Boolean {
        if (pose == null || !pose.isValid()) return false

        val value = extractValue(pose)
        lastValue = value

        return when (exerciseType) {
            ExerciseType.SQUAT -> updateAngleBased(
                value, SQUAT_DOWN_THRESHOLD, SQUAT_UP_THRESHOLD,
                valueAtDown = false  // sudut kecil = DOWN
            )
            ExerciseType.BICEP_CURL -> updateAngleBased(
                value, CURL_DOWN_THRESHOLD, CURL_UP_THRESHOLD,
                valueAtDown = false  // sudut kecil = DOWN (siku tekuk)
            )
            ExerciseType.LATERAL_RAISE -> updateLevelBased(
                value, RAISE_UP_THRESHOLD, RAISE_DOWN_THRESHOLD
            )
            ExerciseType.SHOULDER_PRESS -> updateAngleBased(
                value, PRESS_DOWN_THRESHOLD, PRESS_UP_THRESHOLD,
                valueAtDown = true   // sudut kecil = DOWN (siku tekuk di bawah)
            )
        }
    }

    /**
     * Untuk gerakan berbasis SUDUT (squat, curl, press):
     *   valueAtDown = false → sudut kecil berarti DOWN (squat, curl)
     *   valueAtDown = true  → sudut kecil berarti UP, besar berarti DOWN (press: siku kecil = posisi awal)
     *
     * Satu rep: DOWN → UP → DOWN (terhitung saat kembali ke UP)
     * Lebih tepatnya: dimulai UP, masuk DOWN, kembali UP = +1 rep
     */
    private fun updateAngleBased(
        angle: Float,
        downThreshold: Float,
        upThreshold: Float,
        valueAtDown: Boolean
    ): Boolean {
        val isDown = if (valueAtDown) angle < downThreshold else angle < downThreshold
        val isUp   = if (valueAtDown) angle > upThreshold   else angle > upThreshold

        return when (currentState) {
            PhaseState.UP -> {
                if (isDown) currentState = PhaseState.DOWN
                false
            }
            PhaseState.DOWN -> {
                if (isUp) {
                    currentState = PhaseState.UP
                    repCount++
                    true
                } else false
            }
        }
    }

    /**
     * Untuk gerakan berbasis LEVEL (lateral raise: 0.0 = bawah, 1.0 = atas):
     * UP = level tinggi (lengan terangkat)
     * DOWN = level rendah (lengan di bawah)
     * Satu rep: DOWN → UP → DOWN (dihitung saat kembali DOWN)
     */
    private fun updateLevelBased(
        level: Float,
        upThreshold: Float,
        downThreshold: Float
    ): Boolean {
        return when (currentState) {
            PhaseState.UP -> {
                if (level < downThreshold) {
                    currentState = PhaseState.DOWN
                    repCount++
                    true
                } else false
            }
            PhaseState.DOWN -> {
                if (level > upThreshold) {
                    currentState = PhaseState.UP
                }
                false
            }
        }
    }

    /** Ekstrak nilai pengukuran yang relevan dari pose sesuai exercise type. */
    private fun extractValue(pose: PoseResult): Float = when (exerciseType) {
        ExerciseType.SQUAT         -> squatEngine.computeKneeAngle(pose)
        ExerciseType.BICEP_CURL    -> curlEngine.computeElbowAngle(pose)
        ExerciseType.LATERAL_RAISE -> raiseEngine.computeAbductionLevel(pose)
        ExerciseType.SHOULDER_PRESS -> pressEngine.computeAvgElbowAngle(pose)
    }

    fun getRepCount(): Int = repCount
    fun getCurrentState(): PhaseState = currentState
    fun getLastValue(): Float = lastValue

    fun reset() {
        repCount = 0
        currentState = PhaseState.UP
        lastValue = 0f
    }
}
