package com.modul.gymai.engine

import android.os.SystemClock
import com.modul.gymai.pose.Keypoint
import com.modul.gymai.pose.PoseResult
import com.modul.gymai.utils.AngleUtils
import kotlin.math.abs

class ShoulderPressRuleEngine(
    private val nowProvider: () -> Long = { SystemClock.elapsedRealtime() }
) : ExerciseRuleEngine {

    companion object {
        private const val MIN_CONF = 0.45f
        private const val FRONT_VIEW_SHOULDER_DISTANCE_MIN = 0.14f
        private const val READY_FRAMES = 4
        private const val READY_ELBOW_MAX = 118f
        private const val START_ELBOW_MIN = 126f
        private const val ELBOW_PEAK_MIN = 160f
        private const val ELBOW_PEAK_MAX = 180f
        private const val ARM_TORSO_PEAK_MIN = 150f
        private const val ARM_TORSO_PEAK_MAX = 180f
        private const val RETURN_ELBOW_MAX = 118f
        private const val TORSO_STABILITY_THRESHOLD = 10f
        private const val ELBOW_SYMMETRY_THRESHOLD = 18f
        private const val ARM_TORSO_SYMMETRY_THRESHOLD = 18f
        private const val WRIST_HEIGHT_SYMMETRY_THRESHOLD = 0.08f
        private const val MIN_UP_PHASE_MS = 350L
        private const val MIN_FULL_REP_MS = 850L
        private const val VIOLATION_THRESHOLD_MS = 220L
        private const val VIOLATION_RATIO_THRESHOLD = 0.24f
    }

    private var cycleActive = false
    private var cycleStartTimeMs = 0L
    private var cyclePeakTimeMs = 0L
    private var cycleLastSampleTimeMs = 0L
    private var readyFrames = 0
    private var peakReached = false
    private var tempoViolationDetected = false
    private var anchorTorsoAngle = 0f
    private var torsoViolationMs = 0L
    private var symmetryViolationMs = 0L

    override fun validate(pose: PoseResult?): RuleResult {
        if (pose == null || !pose.isValid()) {
            cancelCycle()
            return RuleResult(
                isValid = false,
                feedback = "Pastikan seluruh tubuh terlihat di kamera",
                liveFeedback = "Pastikan seluruh tubuh terlihat di kamera",
                repStatus = currentRepStatus()
            )
        }

        val metrics = extractMetrics(pose.rawKeypoints) ?: run {
            cancelCycle()
            return RuleResult(
                isValid = false,
                feedback = "Pastikan kedua lengan terlihat jelas",
                liveFeedback = "Pastikan kedua lengan terlihat jelas",
                repStatus = currentRepStatus()
            )
        }

        if (metrics.shoulderDistance < FRONT_VIEW_SHOULDER_DISTANCE_MIN) {
            cancelCycle()
            return RuleResult(
                isValid = false,
                feedback = "Hadapkan tubuh ke depan kamera",
                liveFeedback = "Hadapkan tubuh ke depan kamera",
                repStatus = currentRepStatus()
            )
        }

        val now = nowProvider()
        if (!cycleActive && canStartCycle(metrics)) {
            startCycle(now, metrics)
        } else {
            updateReadyState(metrics)
        }

        val torsoStableNow = isTorsoStable(metrics)
        val symmetricNow = isSymmetric(metrics)
        val topRangeReachedNow = isTopRange(metrics)

        if (cycleActive) {
            val dtMs = (now - cycleLastSampleTimeMs).coerceAtLeast(0L)
            cycleLastSampleTimeMs = now
            accumulateViolations(dtMs, torsoStableNow, symmetricNow)

            if (now > cycleStartTimeMs && topRangeReachedNow) {
                peakReached = true
            }
            if (cyclePeakTimeMs == 0L && now > cycleStartTimeMs && topRangeReachedNow) {
                cyclePeakTimeMs = now
                if (cyclePeakTimeMs - cycleStartTimeMs < MIN_UP_PHASE_MS) {
                    tempoViolationDetected = true
                }
            }
        }

        if (cycleActive && !peakReached && isBackToBottom(metrics)) {
            val resetFeedback = when {
                !torsoStableNow -> "Dorong beban sampai hampir lurus dan jaga punggung tetap stabil"
                !symmetricNow -> "Jaga dorongan kedua lengan tetap simetris"
                else -> "Siap untuk repetisi berikutnya"
            }
            cancelCycle()
            return RuleResult(
                isValid = torsoStableNow && symmetricNow,
                feedback = resetFeedback,
                primaryMetric = metrics.avgElbowAngle,
                secondaryMetric = metrics.avgArmTorsoAngle,
                torsoAngle = metrics.torsoAngle,
                liveFeedback = resetFeedback,
                repStatus = currentRepStatus()
            )
        }

        if (cycleActive && peakReached && isBackToBottom(metrics)) {
            val completedViolation = finishCycle(now)
            val finalFeedback = buildFeedback(
                error = completedViolation,
                defaultMessage = "Dorongan hampir lurus ke atas dan postur stabil"
            )
            val repStatus = if (completedViolation == null) BicepRepStatus.REP_GOOD else BicepRepStatus.REP_BAD
            val shouldCountRep = completedViolation == null
            return RuleResult(
                isValid = shouldCountRep,
                feedback = finalFeedback,
                primaryMetric = metrics.avgElbowAngle,
                secondaryMetric = metrics.avgArmTorsoAngle,
                torsoAngle = metrics.torsoAngle,
                liveFeedback = finalFeedback,
                repStatus = repStatus,
                repCompleted = true,
                shouldCountRep = shouldCountRep
            )
        }

        val liveFeedback = when {
            tempoViolationDetected -> "Tempo terlalu cepat, perlambat gerakan"
            !torsoStableNow -> "Dorong beban sampai hampir lurus dan jaga punggung tetap stabil"
            !symmetricNow -> "Jaga dorongan kedua lengan tetap simetris"
            cycleActive && !peakReached -> "Dorong beban sampai hampir lurus dan jaga punggung tetap stabil"
            cycleActive -> "Dorong beban ke atas dengan kontrol"
            else -> "Siap untuk repetisi berikutnya"
        }

        val liveFormValid = when {
            !cycleActive -> true
            else -> torsoStableNow && symmetricNow
        }

        return RuleResult(
            isValid = liveFormValid,
            feedback = liveFeedback,
            primaryMetric = metrics.avgElbowAngle,
            secondaryMetric = metrics.avgArmTorsoAngle,
            torsoAngle = metrics.torsoAngle,
            liveFeedback = liveFeedback,
            repStatus = currentRepStatus()
        )
    }

    override fun calculateMetric(pose: PoseResult): Float {
        return extractMetrics(pose.rawKeypoints)?.avgElbowAngle ?: 90f
    }

    override fun reset() {
        cycleActive = false
        cycleStartTimeMs = 0L
        cyclePeakTimeMs = 0L
        cycleLastSampleTimeMs = 0L
        readyFrames = 0
        peakReached = false
        tempoViolationDetected = false
        anchorTorsoAngle = 0f
        torsoViolationMs = 0L
        symmetryViolationMs = 0L
    }

    private fun extractMetrics(keypoints: List<Keypoint>): RepMetrics? {
        val leftShoulder = keypoints[Keypoint.LEFT_SHOULDER]
        val rightShoulder = keypoints[Keypoint.RIGHT_SHOULDER]
        val leftElbow = keypoints[Keypoint.LEFT_ELBOW]
        val rightElbow = keypoints[Keypoint.RIGHT_ELBOW]
        val leftWrist = keypoints[Keypoint.LEFT_WRIST]
        val rightWrist = keypoints[Keypoint.RIGHT_WRIST]
        val leftHip = keypoints[Keypoint.LEFT_HIP]
        val rightHip = keypoints[Keypoint.RIGHT_HIP]

        if (
            leftShoulder.confidence <= MIN_CONF ||
            rightShoulder.confidence <= MIN_CONF ||
            leftElbow.confidence <= MIN_CONF ||
            rightElbow.confidence <= MIN_CONF ||
            leftWrist.confidence <= MIN_CONF ||
            rightWrist.confidence <= MIN_CONF ||
            leftHip.confidence <= MIN_CONF ||
            rightHip.confidence <= MIN_CONF
        ) {
            return null
        }

        val leftUpperArm = AngleUtils.distance(leftShoulder.x, leftShoulder.y, leftElbow.x, leftElbow.y)
        val rightUpperArm = AngleUtils.distance(rightShoulder.x, rightShoulder.y, rightElbow.x, rightElbow.y)
        val leftForearm = AngleUtils.distance(leftElbow.x, leftElbow.y, leftWrist.x, leftWrist.y)
        val rightForearm = AngleUtils.distance(rightElbow.x, rightElbow.y, rightWrist.x, rightWrist.y)
        if (
            leftUpperArm < 0.03f ||
            rightUpperArm < 0.03f ||
            leftForearm < 0.03f ||
            rightForearm < 0.03f
        ) {
            return null
        }

        val leftElbowAngle = AngleUtils.angleBetween(
            leftShoulder.x, leftShoulder.y,
            leftElbow.x, leftElbow.y,
            leftWrist.x, leftWrist.y
        )
        val rightElbowAngle = AngleUtils.angleBetween(
            rightShoulder.x, rightShoulder.y,
            rightElbow.x, rightElbow.y,
            rightWrist.x, rightWrist.y
        )

        val leftArmTorsoAngle = AngleUtils.angleBetween(
            leftHip.x, leftHip.y,
            leftShoulder.x, leftShoulder.y,
            leftWrist.x, leftWrist.y
        )
        val rightArmTorsoAngle = AngleUtils.angleBetween(
            rightHip.x, rightHip.y,
            rightShoulder.x, rightShoulder.y,
            rightWrist.x, rightWrist.y
        )

        val shoulderDistance = abs(leftShoulder.x - rightShoulder.x)
        val midShoulderX = (leftShoulder.x + rightShoulder.x) / 2f
        val midShoulderY = (leftShoulder.y + rightShoulder.y) / 2f
        val midHipX = (leftHip.x + rightHip.x) / 2f
        val midHipY = (leftHip.y + rightHip.y) / 2f
        val torsoAngle = AngleUtils.verticalAngle(midHipX, midHipY, midShoulderX, midShoulderY)

        return RepMetrics(
            shoulderDistance = shoulderDistance,
            avgElbowAngle = (leftElbowAngle + rightElbowAngle) / 2f,
            avgArmTorsoAngle = (leftArmTorsoAngle + rightArmTorsoAngle) / 2f,
            elbowDiff = abs(leftElbowAngle - rightElbowAngle),
            armTorsoDiff = abs(leftArmTorsoAngle - rightArmTorsoAngle),
            wristHeightDiff = abs(leftWrist.y - rightWrist.y),
            torsoAngle = torsoAngle
        )
    }

    private fun updateReadyState(metrics: RepMetrics) {
        if (cycleActive) return

        if (metrics.avgElbowAngle <= READY_ELBOW_MAX) {
            readyFrames = (readyFrames + 1).coerceAtMost(READY_FRAMES + 2)
        } else {
            readyFrames = 0
        }
    }

    private fun canStartCycle(metrics: RepMetrics): Boolean {
        return readyFrames >= READY_FRAMES && metrics.avgElbowAngle >= START_ELBOW_MIN
    }

    private fun startCycle(now: Long, metrics: RepMetrics) {
        cycleActive = true
        cycleStartTimeMs = now
        cyclePeakTimeMs = 0L
        cycleLastSampleTimeMs = now
        readyFrames = 0
        peakReached = false
        tempoViolationDetected = false
        anchorTorsoAngle = metrics.torsoAngle
        torsoViolationMs = 0L
        symmetryViolationMs = 0L
    }

    private fun accumulateViolations(dtMs: Long, torsoStableNow: Boolean, symmetricNow: Boolean) {
        if (dtMs <= 0L) return
        if (!torsoStableNow) torsoViolationMs += dtMs
        if (!symmetricNow) symmetryViolationMs += dtMs
    }

    private fun finishCycle(now: Long): ShoulderPressFormError? {
        val fullRepDuration = now - cycleStartTimeMs
        if (cyclePeakTimeMs > 0L && fullRepDuration < MIN_FULL_REP_MS) {
            tempoViolationDetected = true
        }

        val error = determineCompletedViolation(fullRepDuration)
        reset()
        return error
    }

    private fun determineCompletedViolation(fullRepDurationMs: Long): ShoulderPressFormError? {
        if (tempoViolationDetected) {
            return ShoulderPressFormError.TEMPO_TOO_FAST
        }
        if (!peakReached) {
            return ShoulderPressFormError.RANGE_INCOMPLETE
        }
        if (hasSignificantViolation(torsoViolationMs, fullRepDurationMs)) {
            return ShoulderPressFormError.TORSO_UNSTABLE
        }
        if (hasSignificantViolation(symmetryViolationMs, fullRepDurationMs)) {
            return ShoulderPressFormError.ASYMMETRIC
        }
        return null
    }

    private fun hasSignificantViolation(durationMs: Long, fullRepDurationMs: Long): Boolean {
        if (durationMs >= VIOLATION_THRESHOLD_MS) return true
        if (fullRepDurationMs <= 0L) return false
        return durationMs.toFloat() / fullRepDurationMs.toFloat() >= VIOLATION_RATIO_THRESHOLD
    }

    private fun isTopRange(metrics: RepMetrics): Boolean {
        return metrics.avgElbowAngle in ELBOW_PEAK_MIN..ELBOW_PEAK_MAX &&
            metrics.avgArmTorsoAngle in ARM_TORSO_PEAK_MIN..ARM_TORSO_PEAK_MAX
    }

    private fun isBackToBottom(metrics: RepMetrics): Boolean {
        return metrics.avgElbowAngle <= RETURN_ELBOW_MAX
    }

    private fun isTorsoStable(metrics: RepMetrics): Boolean {
        return abs(metrics.torsoAngle - anchorTorsoAngle) <= TORSO_STABILITY_THRESHOLD
    }

    private fun isSymmetric(metrics: RepMetrics): Boolean {
        return metrics.elbowDiff <= ELBOW_SYMMETRY_THRESHOLD &&
            metrics.armTorsoDiff <= ARM_TORSO_SYMMETRY_THRESHOLD &&
            metrics.wristHeightDiff <= WRIST_HEIGHT_SYMMETRY_THRESHOLD
    }

    private fun buildFeedback(error: ShoulderPressFormError?, defaultMessage: String): String {
        return when (error) {
            ShoulderPressFormError.RANGE_INCOMPLETE,
            ShoulderPressFormError.TORSO_UNSTABLE -> "Dorong beban sampai hampir lurus dan jaga punggung tetap stabil"
            ShoulderPressFormError.ASYMMETRIC -> "Jaga dorongan kedua lengan tetap simetris"
            ShoulderPressFormError.TEMPO_TOO_FAST -> "Tempo terlalu cepat, perlambat gerakan"
            null -> defaultMessage
        }
    }

    private fun cancelCycle() {
        reset()
    }

    private fun currentRepStatus(): BicepRepStatus {
        return if (cycleActive) BicepRepStatus.IN_PROGRESS else BicepRepStatus.IDLE
    }

    private data class RepMetrics(
        val shoulderDistance: Float,
        val avgElbowAngle: Float,
        val avgArmTorsoAngle: Float,
        val elbowDiff: Float,
        val armTorsoDiff: Float,
        val wristHeightDiff: Float,
        val torsoAngle: Float
    )

    private enum class ShoulderPressFormError {
        RANGE_INCOMPLETE,
        TORSO_UNSTABLE,
        ASYMMETRIC,
        TEMPO_TOO_FAST
    }
}
