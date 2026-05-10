package com.modul.gymai.engine

import android.os.SystemClock
import com.modul.gymai.pose.Keypoint
import com.modul.gymai.pose.PoseResult
import com.modul.gymai.utils.AngleUtils
import kotlin.math.abs

class SquatRuleEngine(
    private val nowProvider: () -> Long = { SystemClock.elapsedRealtime() }
) : ExerciseRuleEngine {

    companion object {
        private const val MIN_CONF = 0.45f
        private const val READY_FRAMES = 2
        private const val STANDING_KNEE_MIN = 150f
        private const val STANDING_HIP_MIN = 135f
        private const val START_DESCENT_KNEE_MAX = 145f
        private const val START_DESCENT_HIP_MAX = 130f
        private const val KNEE_PEAK_MIN = 80f
        private const val KNEE_PEAK_MAX = 125f
        private const val HIP_PEAK_MIN = 80f
        private const val HIP_PEAK_MAX = 135f
        private const val HIP_FORM_INVALID_MIN = 50f
        private const val RETURN_STANDING_KNEE_MIN = 160f
        private const val RETURN_STANDING_HIP_MIN = 145f
        private const val TORSO_STABILITY_THRESHOLD = 20f
        private const val MIN_DOWN_PHASE_MS = 250L
        private const val MIN_INCOMPLETE_REP_MS = 900L
        private const val MIN_FULL_REP_MS = 700L
        private const val VIOLATION_THRESHOLD_MS = 450L
        private const val VIOLATION_RATIO_THRESHOLD = 0.45f
    }

    private var cycleActive = false
    private var cycleStartTimeMs = 0L
    private var cyclePeakTimeMs = 0L
    private var cycleLastSampleTimeMs = 0L
    private var readyFrames = 0
    private var bottomReached = false
    private var tempoViolationDetected = false
    private var anchorTorsoAngle = 0f
    private var torsoViolationMs = 0L
    private var torsoBentLocked = false
    private var minHipAngleInCycle = 180f

    override fun validate(pose: PoseResult?): RuleResult {
        if (pose == null || !pose.isValid()) {
            cancelCycle()
            return RuleResult(
                isValid = false,
                feedback = "Pastikan seluruh tubuh terlihat di kamera",
                liveFeedback = "Pastikan seluruh tubuh terlihat di kamera",
                repStatus = currentRepStatus(),
                isPositionIssue = true
            )
        }

        val metrics = extractMetrics(pose.rawKeypoints) ?: run {
            cancelCycle()
            return RuleResult(
                isValid = false,
                feedback = "Harus menghadap ke samping serong",
                liveFeedback = "Harus menghadap ke samping serong",
                repStatus = currentRepStatus(),
                isPositionIssue = true
            )
        }

        val now = nowProvider()
        if (!cycleActive && canStartCycle(metrics)) {
            startCycle(now, metrics)
        } else {
            updateReadyState(metrics)
        }

        val torsoStableNow = isTorsoStable(metrics)
        val bottomRangeReachedNow = isBottomRange(metrics)
        val backToStandingNow = isStanding(metrics)

        if (cycleActive) {
            val dtMs = (now - cycleLastSampleTimeMs).coerceAtLeast(0L)
            cycleLastSampleTimeMs = now
            accumulateViolations(dtMs, torsoStableNow)
            minHipAngleInCycle = minOf(minHipAngleInCycle, metrics.avgHipAngle)

            if (now > cycleStartTimeMs && bottomRangeReachedNow) {
                bottomReached = true
            }
            if (cyclePeakTimeMs == 0L && now > cycleStartTimeMs && bottomRangeReachedNow) {
                cyclePeakTimeMs = now
                if (cyclePeakTimeMs - cycleStartTimeMs < MIN_DOWN_PHASE_MS) {
                    tempoViolationDetected = true
                }
            }
        }

        if (cycleActive && !bottomReached && backToStandingNow) {
            val fullRepDuration = (now - cycleStartTimeMs).coerceAtLeast(0L)
            if (fullRepDuration < MIN_INCOMPLETE_REP_MS) {
                resetCycleState(keepStandingReady = true)
                return RuleResult(
                    isValid = true,
                    feedback = "Siap untuk repetisi berikutnya",
                    primaryMetric = metrics.avgKneeAngle,
                    secondaryMetric = metrics.avgHipAngle,
                    torsoAngle = metrics.torsoAngle,
                    liveFeedback = "Siap untuk repetisi berikutnya",
                    repStatus = currentRepStatus()
                )
            }
            val torsoViolationDetected = hasSignificantViolation(torsoViolationMs, fullRepDuration)
            val resetFeedback = if (torsoViolationDetected) {
                "Badan terlalu membungkuk, jaga badan tetap tegak"
            } else {
                "Turunkan pinggul lebih dalam"
            }
            resetCycleState(keepStandingReady = true)
            return RuleResult(
                isValid = false,
                feedback = resetFeedback,
                primaryMetric = metrics.avgKneeAngle,
                secondaryMetric = metrics.avgHipAngle,
                torsoAngle = metrics.torsoAngle,
                liveFeedback = resetFeedback,
                repStatus = BicepRepStatus.REP_BAD,
                repCompleted = true,
                shouldCountRep = false
            )
        }

        if (cycleActive && bottomReached && backToStandingNow) {
            val completedViolation = finishCycle(now)
            val finalFeedback = buildFeedback(
                error = completedViolation,
                defaultMessage = "Gerakan benar, kedalaman squat cukup, badan stabil, dan tempo terkontrol"
            )
            val repStatus = if (completedViolation == null) BicepRepStatus.REP_GOOD else BicepRepStatus.REP_BAD
            val shouldCountRep = completedViolation == null
            return RuleResult(
                isValid = shouldCountRep,
                feedback = finalFeedback,
                primaryMetric = metrics.avgKneeAngle,
                secondaryMetric = metrics.avgHipAngle,
                torsoAngle = metrics.torsoAngle,
                liveFeedback = finalFeedback,
                repStatus = repStatus,
                repCompleted = true,
                shouldCountRep = shouldCountRep
            )
        }

        val liveFeedback = when {
            cycleActive -> "Lanjutkan squat dengan kontrol"
            else -> "Siap untuk repetisi berikutnya"
        }

        val liveFormValid = when {
            !cycleActive -> true
            else -> torsoStableNow
        }

        return RuleResult(
            isValid = liveFormValid,
            feedback = liveFeedback,
            primaryMetric = metrics.avgKneeAngle,
            secondaryMetric = metrics.avgHipAngle,
            torsoAngle = metrics.torsoAngle,
            liveFeedback = liveFeedback,
            repStatus = currentRepStatus()
        )
    }

    override fun calculateMetric(pose: PoseResult): Float {
        return extractMetrics(pose.rawKeypoints)?.avgKneeAngle ?: 180f
    }

    override fun reset() {
        resetCycleState()
    }

    private fun resetCycleState(keepStandingReady: Boolean = false) {
        cycleActive = false
        cycleStartTimeMs = 0L
        cyclePeakTimeMs = 0L
        cycleLastSampleTimeMs = 0L
        readyFrames = if (keepStandingReady) READY_FRAMES else 0
        bottomReached = false
        tempoViolationDetected = false
        anchorTorsoAngle = 0f
        torsoViolationMs = 0L
        torsoBentLocked = false
        minHipAngleInCycle = 180f
    }

    private fun extractMetrics(keypoints: List<Keypoint>): RepMetrics? {
        val leftHip = keypoints[Keypoint.LEFT_HIP]
        val rightHip = keypoints[Keypoint.RIGHT_HIP]
        val leftKnee = keypoints[Keypoint.LEFT_KNEE]
        val rightKnee = keypoints[Keypoint.RIGHT_KNEE]
        val leftAnkle = keypoints[Keypoint.LEFT_ANKLE]
        val rightAnkle = keypoints[Keypoint.RIGHT_ANKLE]
        val leftShoulder = keypoints[Keypoint.LEFT_SHOULDER]
        val rightShoulder = keypoints[Keypoint.RIGHT_SHOULDER]

        if (
            leftHip.confidence <= MIN_CONF ||
            rightHip.confidence <= MIN_CONF ||
            leftKnee.confidence <= MIN_CONF ||
            rightKnee.confidence <= MIN_CONF ||
            leftAnkle.confidence <= MIN_CONF ||
            rightAnkle.confidence <= MIN_CONF ||
            leftShoulder.confidence <= MIN_CONF ||
            rightShoulder.confidence <= MIN_CONF
        ) {
            return null
        }

        val leftUpperLeg = AngleUtils.distance(leftHip.x, leftHip.y, leftKnee.x, leftKnee.y)
        val rightUpperLeg = AngleUtils.distance(rightHip.x, rightHip.y, rightKnee.x, rightKnee.y)
        val leftLowerLeg = AngleUtils.distance(leftKnee.x, leftKnee.y, leftAnkle.x, leftAnkle.y)
        val rightLowerLeg = AngleUtils.distance(rightKnee.x, rightKnee.y, rightAnkle.x, rightAnkle.y)
        if (
            leftUpperLeg < 0.03f ||
            rightUpperLeg < 0.03f ||
            leftLowerLeg < 0.03f ||
            rightLowerLeg < 0.03f
        ) {
            return null
        }

        val leftKneeAngle = AngleUtils.angleBetween(
            leftHip.x, leftHip.y,
            leftKnee.x, leftKnee.y,
            leftAnkle.x, leftAnkle.y
        )
        val rightKneeAngle = AngleUtils.angleBetween(
            rightHip.x, rightHip.y,
            rightKnee.x, rightKnee.y,
            rightAnkle.x, rightAnkle.y
        )

        val leftHipAngle = AngleUtils.angleBetween(
            leftShoulder.x, leftShoulder.y,
            leftHip.x, leftHip.y,
            leftKnee.x, leftKnee.y
        )
        val rightHipAngle = AngleUtils.angleBetween(
            rightShoulder.x, rightShoulder.y,
            rightHip.x, rightHip.y,
            rightKnee.x, rightKnee.y
        )

        val midShoulderX = (leftShoulder.x + rightShoulder.x) / 2f
        val midShoulderY = (leftShoulder.y + rightShoulder.y) / 2f
        val midHipX = (leftHip.x + rightHip.x) / 2f
        val midHipY = (leftHip.y + rightHip.y) / 2f
        val torsoAngle = AngleUtils.verticalAngle(midHipX, midHipY, midShoulderX, midShoulderY)

        return RepMetrics(
            avgKneeAngle = (leftKneeAngle + rightKneeAngle) / 2f,
            avgHipAngle = (leftHipAngle + rightHipAngle) / 2f,
            torsoAngle = torsoAngle
        )
    }

    private fun updateReadyState(metrics: RepMetrics) {
        if (cycleActive) return

        if (isStanding(metrics)) {
            readyFrames = (readyFrames + 1).coerceAtMost(READY_FRAMES + 2)
        } else if (readyFrames >= READY_FRAMES) {
            readyFrames = READY_FRAMES
        } else {
            readyFrames = 0
        }
    }

    private fun canStartCycle(metrics: RepMetrics): Boolean {
        return readyFrames >= READY_FRAMES &&
            !isStanding(metrics) &&
            (
                metrics.avgKneeAngle <= START_DESCENT_KNEE_MAX ||
                    metrics.avgHipAngle <= START_DESCENT_HIP_MAX
            )
    }

    private fun startCycle(now: Long, metrics: RepMetrics) {
        cycleActive = true
        cycleStartTimeMs = now
        cyclePeakTimeMs = 0L
        cycleLastSampleTimeMs = now
        readyFrames = 0
        bottomReached = false
        tempoViolationDetected = false
        anchorTorsoAngle = metrics.torsoAngle
        torsoViolationMs = 0L
        torsoBentLocked = false
        minHipAngleInCycle = metrics.avgHipAngle
    }

    private fun accumulateViolations(dtMs: Long, torsoStableNow: Boolean) {
        if (dtMs <= 0L) return
        if (!torsoStableNow) {
            torsoViolationMs += dtMs
            if (torsoViolationMs >= VIOLATION_THRESHOLD_MS) {
                torsoBentLocked = true
            }
        }
    }

    private fun finishCycle(now: Long): SquatFormError? {
        val fullRepDuration = now - cycleStartTimeMs
        if (cyclePeakTimeMs > 0L && fullRepDuration < MIN_FULL_REP_MS) {
            tempoViolationDetected = true
        }

        val error = determineCompletedViolation(fullRepDuration)
        resetCycleState(keepStandingReady = true)
        return error
    }

    private fun determineCompletedViolation(fullRepDurationMs: Long): SquatFormError? {
        if (minHipAngleInCycle < HIP_FORM_INVALID_MIN) {
            return SquatFormError.TORSO_BENT
        }
        if (hasSignificantViolation(torsoViolationMs, fullRepDurationMs)) {
            return SquatFormError.TORSO_BENT
        }
        if (tempoViolationDetected) {
            return SquatFormError.TEMPO_TOO_FAST
        }
        if (!bottomReached) {
            return SquatFormError.RANGE_INCOMPLETE
        }
        return null
    }

    private fun hasSignificantViolation(durationMs: Long, fullRepDurationMs: Long): Boolean {
        if (torsoBentLocked) return true
        if (durationMs >= VIOLATION_THRESHOLD_MS) return true
        if (fullRepDurationMs <= 0L) return false
        return durationMs.toFloat() / fullRepDurationMs.toFloat() >= VIOLATION_RATIO_THRESHOLD
    }

    private fun isStanding(metrics: RepMetrics): Boolean {
        return metrics.avgKneeAngle >= STANDING_KNEE_MIN &&
            metrics.avgHipAngle >= STANDING_HIP_MIN
    }

    private fun isBottomRange(metrics: RepMetrics): Boolean {
        return metrics.avgKneeAngle in KNEE_PEAK_MIN..KNEE_PEAK_MAX &&
            metrics.avgHipAngle in HIP_PEAK_MIN..HIP_PEAK_MAX
    }

    private fun isTorsoStable(metrics: RepMetrics): Boolean {
        return abs(metrics.torsoAngle - anchorTorsoAngle) <= TORSO_STABILITY_THRESHOLD
    }

    private fun buildFeedback(error: SquatFormError?, defaultMessage: String): String {
        return when (error) {
            SquatFormError.RANGE_INCOMPLETE -> "Turunkan pinggul lebih dalam"
            SquatFormError.TORSO_BENT -> "Badan terlalu membungkuk, jaga badan tetap tegak"
            SquatFormError.TEMPO_TOO_FAST -> "Tempo terlalu cepat, perlambat gerakan"
            null -> defaultMessage
        }
    }

    private fun cancelCycle() {
        resetCycleState()
    }

    private fun currentRepStatus(): BicepRepStatus {
        return if (cycleActive) BicepRepStatus.IN_PROGRESS else BicepRepStatus.IDLE
    }

    private data class RepMetrics(
        val avgKneeAngle: Float,
        val avgHipAngle: Float,
        val torsoAngle: Float
    )

    private enum class SquatFormError {
        RANGE_INCOMPLETE,
        TORSO_BENT,
        TEMPO_TOO_FAST
    }
}
