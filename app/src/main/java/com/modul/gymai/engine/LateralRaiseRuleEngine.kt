package com.modul.gymai.engine

import android.os.SystemClock
import com.modul.gymai.pose.Keypoint
import com.modul.gymai.pose.PoseResult
import com.modul.gymai.utils.AngleUtils
import kotlin.math.abs

class LateralRaiseRuleEngine(
    private val nowProvider: () -> Long = { SystemClock.elapsedRealtime() }
) : ExerciseRuleEngine {

    companion object {
        private const val MIN_CONF = 0.45f
        private const val FRONT_VIEW_SHOULDER_DISTANCE_MIN = 0.14f
        private const val READY_FRAMES = 4
        private const val READY_LIFT_MAX = 24f
        private const val START_LIFT_MIN = 28f
        private const val PEAK_LIFT_MIN = 70f
        private const val PEAK_LIFT_MAX = 100f
        private const val OVER_RAISE_MAX = 110f
        private const val RETURN_LIFT_MAX = 24f
        private const val ELBOW_BEND_MIN = 80f
        private const val ELBOW_BEND_MAX = 165f
        private const val SHOULDER_SHRUG_THRESHOLD = 0.028f
        private const val HIP_CENTER_DRIFT_THRESHOLD = 0.075f
        private const val TORSO_ANGLE_DRIFT_THRESHOLD = 10f
        private const val SYMMETRY_LIFT_THRESHOLD = 16f
        private const val WRIST_HEIGHT_SYMMETRY_THRESHOLD = 0.065f
        private const val ELBOW_LEAD_TOLERANCE = -0.012f
        private const val PEAK_OVER_SHOULDER_TOLERANCE = 0.02f
        private const val SCAPTION_WRIST_OUTER_RATIO = 0.75f
        private const val SCAPTION_ELBOW_OUTER_RATIO = 0.58f
        private const val ACTIVE_LIFT_MIN = 38f
        private const val MIN_UP_PHASE_MS = 350L
        private const val MIN_FULL_REP_MS = 800L
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
    private var overRaiseDetected = false
    private var anchorShoulderY = 0f
    private var anchorHipCenterX = 0f
    private var anchorHipCenterY = 0f
    private var anchorTorsoAngle = 0f

    private var elbowBendViolationMs = 0L
    private var elbowLeadingViolationMs = 0L
    private var shrugViolationMs = 0L
    private var torsoViolationMs = 0L
    private var pathViolationMs = 0L
    private var symmetryViolationMs = 0L

    override fun validate(pose: PoseResult?): RuleResult {
        if (pose == null || !pose.isValid()) {
            cancelCycle()
            return RuleResult(
                isValid = false,
                feedback = "Pastikan tubuh terlihat jelas di kamera",
                liveFeedback = "Pastikan tubuh terlihat jelas di kamera",
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

        val elbowBentNow = isBentEnough(metrics)
        val elbowLeadingNow = metrics.avgLiftAngle < ACTIVE_LIFT_MIN || isElbowLeading(metrics)
        val pathControlledNow = metrics.avgLiftAngle < ACTIVE_LIFT_MIN || isScaptionPathValid(metrics)
        val shouldersRelaxedNow = !areShouldersShrugging(metrics)
        val torsoStableNow = isTorsoStable(metrics)
        val symmetricNow = isSymmetric(metrics)

        if (cycleActive) {
            val dtMs = (now - cycleLastSampleTimeMs).coerceAtLeast(0L)
            cycleLastSampleTimeMs = now
            accumulateViolations(
                dtMs = dtMs,
                elbowBentNow = elbowBentNow,
                elbowLeadingNow = elbowLeadingNow,
                shouldersRelaxedNow = shouldersRelaxedNow,
                torsoStableNow = torsoStableNow,
                pathControlledNow = pathControlledNow,
                symmetricNow = symmetricNow
            )

            if (metrics.avgLiftAngle >= PEAK_LIFT_MIN) {
                peakReached = true
            }
            if (cyclePeakTimeMs == 0L && metrics.avgLiftAngle >= PEAK_LIFT_MIN) {
                cyclePeakTimeMs = now
                if (cyclePeakTimeMs - cycleStartTimeMs < MIN_UP_PHASE_MS) {
                    tempoViolationDetected = true
                }
            }
            if (isRaisedTooHigh(metrics)) {
                overRaiseDetected = true
            }
        }

        if (cycleActive && !peakReached && metrics.avgLiftAngle <= READY_LIFT_MAX) {
            val resetFeedback = if (elbowBentNow) {
                "Siap untuk repetisi berikutnya"
            } else {
                "Jaga siku tetap sedikit menekuk"
            }
            cancelCycle()
            return RuleResult(
                isValid = elbowBentNow,
                feedback = resetFeedback,
                primaryMetric = metrics.avgLiftAngle,
                secondaryMetric = metrics.avgElbowAngle,
                torsoAngle = metrics.torsoAngle,
                liveFeedback = resetFeedback,
                repStatus = currentRepStatus()
            )
        }

        if (cycleActive && peakReached && metrics.avgLiftAngle <= RETURN_LIFT_MAX) {
            val completedViolation = finishCycle(now)
            val finalFeedback = buildFeedback(
                error = completedViolation,
                defaultMessage = "Lengan terangkat setinggi bahu dan tubuh stabil"
            )
            val repStatus = if (completedViolation == null) BicepRepStatus.REP_GOOD else BicepRepStatus.REP_BAD
            val shouldCountRep = completedViolation == null
            return RuleResult(
                isValid = shouldCountRep,
                feedback = finalFeedback,
                primaryMetric = metrics.avgLiftAngle,
                secondaryMetric = metrics.avgElbowAngle,
                torsoAngle = metrics.torsoAngle,
                liveFeedback = finalFeedback,
                repStatus = repStatus,
                repCompleted = true,
                shouldCountRep = shouldCountRep
            )
        }

        val isOverRaisedNow = cycleActive && isRaisedTooHigh(metrics)

        val liveFeedback = when {
            tempoViolationDetected -> "Tempo terlalu cepat, perlambat gerakan"
            !elbowBentNow -> "Jaga siku tetap sedikit menekuk"
            !shouldersRelaxedNow -> "Jangan angkat bahu saat mengangkat beban"
            !torsoStableNow -> "Angkat lengan setinggi bahu dan hindari tubuh condong"
            !pathControlledNow -> "Angkat beban ke samping serong, jangan terlalu ke pinggir"
            !elbowLeadingNow -> "Pimpin gerakan dengan siku, jangan pergelangan tangan"
            !symmetricNow -> "Jaga kedua lengan tetap seimbang"
            isOverRaisedNow -> "Angkat lengan setinggi bahu dan hindari tubuh condong"
            cycleActive && metrics.avgLiftAngle < PEAK_LIFT_MIN -> "Angkat lengan setinggi bahu dan hindari tubuh condong"
            cycleActive -> "Angkat beban dengan kontrol"
            else -> "Siap untuk repetisi berikutnya"
        }

        val liveFormValid = when {
            !cycleActive -> elbowBentNow
            metrics.avgLiftAngle < ACTIVE_LIFT_MIN -> elbowBentNow
            else -> elbowBentNow &&
                shouldersRelaxedNow &&
                torsoStableNow &&
                pathControlledNow &&
                elbowLeadingNow &&
                !isOverRaisedNow
        }

        return RuleResult(
            isValid = liveFormValid,
            feedback = liveFeedback,
            primaryMetric = metrics.avgLiftAngle,
            secondaryMetric = metrics.avgElbowAngle,
            torsoAngle = metrics.torsoAngle,
            liveFeedback = liveFeedback,
            repStatus = currentRepStatus()
        )
    }

    override fun calculateMetric(pose: PoseResult): Float {
        return extractMetrics(pose.rawKeypoints)?.avgLiftAngle ?: 0f
    }

    override fun reset() {
        cycleActive = false
        cycleStartTimeMs = 0L
        cyclePeakTimeMs = 0L
        cycleLastSampleTimeMs = 0L
        readyFrames = 0
        peakReached = false
        tempoViolationDetected = false
        overRaiseDetected = false
        anchorShoulderY = 0f
        anchorHipCenterX = 0f
        anchorHipCenterY = 0f
        anchorTorsoAngle = 0f
        elbowBendViolationMs = 0L
        elbowLeadingViolationMs = 0L
        shrugViolationMs = 0L
        torsoViolationMs = 0L
        pathViolationMs = 0L
        symmetryViolationMs = 0L
    }

    private fun extractMetrics(keypoints: List<Keypoint>): RepMetrics? {
        val left = buildArmMetrics(
            shoulder = keypoints[Keypoint.LEFT_SHOULDER],
            elbow = keypoints[Keypoint.LEFT_ELBOW],
            wrist = keypoints[Keypoint.LEFT_WRIST]
        ) ?: return null
        val right = buildArmMetrics(
            shoulder = keypoints[Keypoint.RIGHT_SHOULDER],
            elbow = keypoints[Keypoint.RIGHT_ELBOW],
            wrist = keypoints[Keypoint.RIGHT_WRIST]
        ) ?: return null

        val leftHip = keypoints[Keypoint.LEFT_HIP]
        val rightHip = keypoints[Keypoint.RIGHT_HIP]
        if (leftHip.confidence <= MIN_CONF || rightHip.confidence <= MIN_CONF) {
            return null
        }

        val shoulderDistance = abs(left.shoulder.x - right.shoulder.x)
        val midShoulderX = (left.shoulder.x + right.shoulder.x) / 2f
        val midShoulderY = (left.shoulder.y + right.shoulder.y) / 2f
        val midHipX = (leftHip.x + rightHip.x) / 2f
        val midHipY = (leftHip.y + rightHip.y) / 2f
        val torsoAngle = AngleUtils.verticalAngle(midHipX, midHipY, midShoulderX, midShoulderY)

        return RepMetrics(
            left = left,
            right = right,
            shoulderDistance = shoulderDistance,
            avgLiftAngle = (left.liftAngle + right.liftAngle) / 2f,
            avgElbowAngle = (left.elbowAngle + right.elbowAngle) / 2f,
            liftAngleDiff = abs(left.liftAngle - right.liftAngle),
            wristHeightDiff = abs(left.wrist.y - right.wrist.y),
            torsoAngle = torsoAngle,
            hipCenterX = midHipX,
            hipCenterY = midHipY,
            avgShoulderY = (left.shoulder.y + right.shoulder.y) / 2f,
            leftWristAboveShoulder = left.wrist.y < left.shoulder.y - PEAK_OVER_SHOULDER_TOLERANCE,
            rightWristAboveShoulder = right.wrist.y < right.shoulder.y - PEAK_OVER_SHOULDER_TOLERANCE,
            leftElbowAboveShoulder = left.elbow.y < left.shoulder.y - PEAK_OVER_SHOULDER_TOLERANCE,
            rightElbowAboveShoulder = right.elbow.y < right.shoulder.y - PEAK_OVER_SHOULDER_TOLERANCE
        )
    }

    private fun buildArmMetrics(shoulder: Keypoint, elbow: Keypoint, wrist: Keypoint): ArmMetrics? {
        if (shoulder.confidence <= MIN_CONF || elbow.confidence <= MIN_CONF || wrist.confidence <= MIN_CONF) {
            return null
        }

        val upperArmLength = AngleUtils.distance(shoulder.x, shoulder.y, elbow.x, elbow.y)
        val forearmLength = AngleUtils.distance(elbow.x, elbow.y, wrist.x, wrist.y)
        if (upperArmLength < 0.03f || forearmLength < 0.03f) {
            return null
        }

        return ArmMetrics(
            shoulder = shoulder,
            elbow = elbow,
            wrist = wrist,
            liftAngle = AngleUtils.verticalAngle(shoulder.x, shoulder.y, elbow.x, elbow.y),
            elbowAngle = AngleUtils.angleBetween(shoulder.x, shoulder.y, elbow.x, elbow.y, wrist.x, wrist.y)
        )
    }

    private fun updateReadyState(metrics: RepMetrics) {
        if (cycleActive) return

        if (metrics.avgLiftAngle <= READY_LIFT_MAX && isBentEnough(metrics)) {
            readyFrames = (readyFrames + 1).coerceAtMost(READY_FRAMES + 2)
        } else {
            readyFrames = 0
        }
    }

    private fun canStartCycle(metrics: RepMetrics): Boolean {
        return readyFrames >= READY_FRAMES && metrics.avgLiftAngle >= START_LIFT_MIN
    }

    private fun startCycle(now: Long, metrics: RepMetrics) {
        cycleActive = true
        cycleStartTimeMs = now
        cyclePeakTimeMs = 0L
        cycleLastSampleTimeMs = now
        peakReached = false
        tempoViolationDetected = false
        overRaiseDetected = false
        anchorShoulderY = metrics.avgShoulderY
        anchorHipCenterX = metrics.hipCenterX
        anchorHipCenterY = metrics.hipCenterY
        anchorTorsoAngle = metrics.torsoAngle
        elbowBendViolationMs = 0L
        elbowLeadingViolationMs = 0L
        shrugViolationMs = 0L
        torsoViolationMs = 0L
        pathViolationMs = 0L
        symmetryViolationMs = 0L
        readyFrames = 0
    }

    private fun accumulateViolations(
        dtMs: Long,
        elbowBentNow: Boolean,
        elbowLeadingNow: Boolean,
        shouldersRelaxedNow: Boolean,
        torsoStableNow: Boolean,
        pathControlledNow: Boolean,
        symmetricNow: Boolean
    ) {
        if (dtMs <= 0L) return

        if (!elbowBentNow) elbowBendViolationMs += dtMs
        if (!elbowLeadingNow) elbowLeadingViolationMs += dtMs
        if (!shouldersRelaxedNow) shrugViolationMs += dtMs
        if (!torsoStableNow) torsoViolationMs += dtMs
        if (!pathControlledNow) pathViolationMs += dtMs
        if (!symmetricNow) symmetryViolationMs += dtMs
    }

    private fun finishCycle(now: Long): LateralRaiseFormError? {
        val fullRepDuration = now - cycleStartTimeMs
        if (cyclePeakTimeMs > 0L && fullRepDuration < MIN_FULL_REP_MS) {
            tempoViolationDetected = true
        }

        val error = determineCompletedViolation(fullRepDuration)
        reset()
        return error
    }

    private fun cancelCycle() {
        reset()
    }

    private fun determineCompletedViolation(fullRepDurationMs: Long): LateralRaiseFormError? {
        if (tempoViolationDetected) {
            return LateralRaiseFormError.TEMPO_TOO_FAST
        }
        if (!peakReached) {
            return LateralRaiseFormError.RANGE_TOO_LOW
        }
        if (overRaiseDetected) {
            return LateralRaiseFormError.RANGE_TOO_HIGH
        }
        if (hasSignificantViolation(elbowBendViolationMs, fullRepDurationMs)) {
            return LateralRaiseFormError.ELBOW_TOO_STRAIGHT
        }
        if (hasSignificantViolation(pathViolationMs, fullRepDurationMs)) {
            return LateralRaiseFormError.PATH_TOO_WIDE
        }
        if (hasSignificantViolation(elbowLeadingViolationMs, fullRepDurationMs)) {
            return LateralRaiseFormError.WRIST_LEADING
        }
        if (hasSignificantViolation(shrugViolationMs, fullRepDurationMs)) {
            return LateralRaiseFormError.SHOULDER_SHRUG
        }
        if (hasSignificantViolation(torsoViolationMs, fullRepDurationMs)) {
            return LateralRaiseFormError.TORSO_SWAY
        }
        if (hasSignificantViolation(symmetryViolationMs, fullRepDurationMs)) {
            return LateralRaiseFormError.ASYMMETRIC
        }
        return null
    }

    private fun hasSignificantViolation(durationMs: Long, fullRepDurationMs: Long): Boolean {
        if (durationMs >= VIOLATION_THRESHOLD_MS) return true
        if (fullRepDurationMs <= 0L) return false
        return durationMs.toFloat() / fullRepDurationMs.toFloat() >= VIOLATION_RATIO_THRESHOLD
    }

    private fun isBentEnough(metrics: RepMetrics): Boolean {
        return metrics.left.elbowAngle in ELBOW_BEND_MIN..ELBOW_BEND_MAX &&
            metrics.right.elbowAngle in ELBOW_BEND_MIN..ELBOW_BEND_MAX
    }

    private fun isElbowLeading(metrics: RepMetrics): Boolean {
        val leftLead = metrics.left.wrist.y - metrics.left.elbow.y
        val rightLead = metrics.right.wrist.y - metrics.right.elbow.y
        return leftLead >= ELBOW_LEAD_TOLERANCE && rightLead >= ELBOW_LEAD_TOLERANCE
    }

    private fun isRaisedTooHigh(metrics: RepMetrics): Boolean {
        return metrics.avgLiftAngle > OVER_RAISE_MAX ||
            metrics.leftWristAboveShoulder || metrics.rightWristAboveShoulder ||
            metrics.leftElbowAboveShoulder || metrics.rightElbowAboveShoulder
    }

    private fun isScaptionPathValid(metrics: RepMetrics): Boolean {
        val wristOuterLimit = metrics.shoulderDistance * SCAPTION_WRIST_OUTER_RATIO
        val elbowOuterLimit = metrics.shoulderDistance * SCAPTION_ELBOW_OUTER_RATIO

        val leftWristTooFar = (metrics.left.shoulder.x - metrics.left.wrist.x) > wristOuterLimit
        val rightWristTooFar = (metrics.right.wrist.x - metrics.right.shoulder.x) > wristOuterLimit
        val leftElbowTooFar = (metrics.left.shoulder.x - metrics.left.elbow.x) > elbowOuterLimit
        val rightElbowTooFar = (metrics.right.elbow.x - metrics.right.shoulder.x) > elbowOuterLimit

        return !leftWristTooFar && !rightWristTooFar && !leftElbowTooFar && !rightElbowTooFar
    }

    private fun areShouldersShrugging(metrics: RepMetrics): Boolean {
        return (anchorShoulderY - metrics.avgShoulderY) > SHOULDER_SHRUG_THRESHOLD
    }

    private fun isTorsoStable(metrics: RepMetrics): Boolean {
        if (metrics.avgLiftAngle < ACTIVE_LIFT_MIN) {
            return true
        }

        val hipCenterDrift = AngleUtils.distance(
            anchorHipCenterX,
            anchorHipCenterY,
            metrics.hipCenterX,
            metrics.hipCenterY
        )
        val torsoAngleDrift = abs(metrics.torsoAngle - anchorTorsoAngle)
        return hipCenterDrift <= HIP_CENTER_DRIFT_THRESHOLD &&
            torsoAngleDrift <= TORSO_ANGLE_DRIFT_THRESHOLD
    }

    private fun isSymmetric(metrics: RepMetrics): Boolean {
        return metrics.liftAngleDiff <= SYMMETRY_LIFT_THRESHOLD &&
            metrics.wristHeightDiff <= WRIST_HEIGHT_SYMMETRY_THRESHOLD
    }

    private fun buildFeedback(error: LateralRaiseFormError?, defaultMessage: String): String {
        return when (error) {
            LateralRaiseFormError.ELBOW_TOO_STRAIGHT -> "Jaga siku tetap sedikit menekuk"
            LateralRaiseFormError.RANGE_TOO_LOW -> "Angkat lengan setinggi bahu dan hindari tubuh condong"
            LateralRaiseFormError.RANGE_TOO_HIGH -> "Angkat lengan setinggi bahu dan hindari tubuh condong"
            LateralRaiseFormError.WRIST_LEADING -> "Pimpin gerakan dengan siku, jangan pergelangan tangan"
            LateralRaiseFormError.SHOULDER_SHRUG -> "Jangan angkat bahu saat mengangkat beban"
            LateralRaiseFormError.TEMPO_TOO_FAST -> "Tempo terlalu cepat, perlambat gerakan"
            LateralRaiseFormError.TORSO_SWAY -> "Angkat lengan setinggi bahu dan hindari tubuh condong"
            LateralRaiseFormError.PATH_TOO_WIDE -> "Angkat beban ke samping serong, jangan terlalu ke pinggir"
            LateralRaiseFormError.ASYMMETRIC -> "Jaga kedua lengan tetap seimbang"
            null -> defaultMessage
        }
    }

    private fun currentRepStatus(): BicepRepStatus {
        return if (cycleActive) BicepRepStatus.IN_PROGRESS else BicepRepStatus.IDLE
    }

    private data class ArmMetrics(
        val shoulder: Keypoint,
        val elbow: Keypoint,
        val wrist: Keypoint,
        val liftAngle: Float,
        val elbowAngle: Float
    )

    private data class RepMetrics(
        val left: ArmMetrics,
        val right: ArmMetrics,
        val shoulderDistance: Float,
        val avgLiftAngle: Float,
        val avgElbowAngle: Float,
        val liftAngleDiff: Float,
        val wristHeightDiff: Float,
        val torsoAngle: Float,
        val hipCenterX: Float,
        val hipCenterY: Float,
        val avgShoulderY: Float,
        val leftWristAboveShoulder: Boolean,
        val rightWristAboveShoulder: Boolean,
        val leftElbowAboveShoulder: Boolean,
        val rightElbowAboveShoulder: Boolean
    )

    private enum class LateralRaiseFormError {
        ELBOW_TOO_STRAIGHT,
        RANGE_TOO_LOW,
        RANGE_TOO_HIGH,
        WRIST_LEADING,
        SHOULDER_SHRUG,
        TEMPO_TOO_FAST,
        TORSO_SWAY,
        PATH_TOO_WIDE,
        ASYMMETRIC
    }
}
