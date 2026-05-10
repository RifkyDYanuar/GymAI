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
        private const val MIN_CONF = 0.35f
        private const val HIP_MIN_CONF = 0.28f
        private const val FRONT_VIEW_SHOULDER_DISTANCE_MIN = 0.10f
        private const val READY_FRAMES = 3
        private const val READY_LIFT_MAX = 28f
        private const val START_LIFT_MIN = 28f
        private const val PEAK_LIFT_MIN = 64f
        private const val PEAK_LIFT_MAX = 100f
        private const val OVER_RAISE_MAX = 118f
        private const val RETURN_LIFT_MAX = 34f
        private const val SHOULDER_SHRUG_THRESHOLD = 0.028f
        private const val HIP_CENTER_DRIFT_THRESHOLD = 0.075f
        private const val TORSO_ANGLE_DRIFT_THRESHOLD = 10f
        private const val SYMMETRY_LIFT_THRESHOLD = 16f
        private const val WRIST_HEIGHT_SYMMETRY_THRESHOLD = 0.065f
        private const val ELBOW_LEAD_TOLERANCE = -0.012f
        private const val PEAK_OVER_SHOULDER_TOLERANCE = 0.06f
        private const val FOREARM_UPWARD_TOLERANCE = 0.03f
        private const val SCAPTION_WRIST_OUTER_RATIO = 0.75f
        private const val SCAPTION_ELBOW_OUTER_RATIO = 0.58f
        private const val ACTIVE_LIFT_MIN = 42f
        private const val RETURN_LIFT_FALLBACK_MAX = ACTIVE_LIFT_MIN
        private const val MIN_UP_PHASE_MS = 350L
        private const val MIN_FULL_REP_MS = 800L
        private const val VIOLATION_THRESHOLD_MS = 220L
        private const val VIOLATION_RATIO_THRESHOLD = 0.24f
        private const val ACTIVE_MISSING_ARM_TOLERANCE_FRAMES = 5
    }

    private var cycleActive = false
    private var cycleStartTimeMs = 0L
    private var cyclePeakTimeMs = 0L
    private var cycleLastSampleTimeMs = 0L
    private var readyFrames = 0
    private var peakReached = false
    private var tempoViolationDetected = false
    private var overRaiseViolationDetected = false
    private var anchorShoulderY = 0f
    private var anchorHipCenterX = 0f
    private var anchorHipCenterY = 0f
    private var anchorTorsoAngle = 0f
    private var elbowLeadingViolationMs = 0L
    private var shrugViolationMs = 0L
    private var torsoViolationMs = 0L
    private var pathViolationMs = 0L
    private var symmetryViolationMs = 0L
    private var overRaiseViolationMs = 0L

    // Sudut angkat tertinggi yang dicapai dalam siklus saat ini
    private var cycleMaxLiftAngle = 0f
    private var requireFreshBottomBeforeNextCycle = false
    private var activeMissingArmFrames = 0

    override fun validate(pose: PoseResult?): RuleResult {
        if (pose == null || !pose.isValid()) {
            cancelCycle()
            return RuleResult(
                isValid = false,
                feedback = "Pastikan tubuh terlihat jelas di kamera",
                liveFeedback = "Pastikan tubuh terlihat jelas di kamera",
                repStatus = currentRepStatus(),
                isPositionIssue = true
            )
        }

        val metrics = extractMetrics(pose.rawKeypoints) ?: run {
            if (cycleActive) {
                activeMissingArmFrames++
                if (activeMissingArmFrames <= ACTIVE_MISSING_ARM_TOLERANCE_FRAMES) {
                    return RuleResult(
                        isValid = true,
                        feedback = "Lanjutkan lateral raise dengan kontrol",
                        liveFeedback = "Lanjutkan lateral raise dengan kontrol",
                        repStatus = BicepRepStatus.IN_PROGRESS,
                        repCompleted = false,
                        shouldCountRep = false,
                        isPositionIssue = false
                    )
                }
            }
            cancelCycle()
            return RuleResult(
                isValid = false,
                feedback = "Pastikan kedua lengan terlihat jelas",
                liveFeedback = "Pastikan kedua lengan terlihat jelas",
                repStatus = currentRepStatus(),
                isPositionIssue = true
            )
        }
        activeMissingArmFrames = 0

        if (metrics.shoulderDistance < FRONT_VIEW_SHOULDER_DISTANCE_MIN) {
            cancelCycle()
            return RuleResult(
                isValid = false,
                feedback = "Hadapkan tubuh ke depan kamera",
                liveFeedback = "Hadapkan tubuh ke depan kamera",
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

        val elbowBentAtReady = isBentEnough(metrics)
        val elbowLeadingNow = metrics.avgLiftAngle < ACTIVE_LIFT_MIN || isElbowLeading(metrics)
        val pathControlledNow = metrics.avgLiftAngle < ACTIVE_LIFT_MIN || isScaptionPathValid(metrics)
        val shouldersRelaxedNow = !areShouldersShrugging(metrics)
        val torsoStableNow = isTorsoStable(metrics)
        val symmetricNow = isSymmetric(metrics)
        val isOverRaisedNow = cycleActive && isRaisedTooHigh(metrics)

        if (cycleActive) {
            val dtMs = (now - cycleLastSampleTimeMs).coerceAtLeast(0L)
            cycleLastSampleTimeMs = now
            cycleMaxLiftAngle = maxOf(cycleMaxLiftAngle, metrics.avgLiftAngle)
            accumulateViolations(
                dtMs = dtMs,
                elbowLeadingNow = elbowLeadingNow,
                shouldersRelaxedNow = shouldersRelaxedNow,
                torsoStableNow = torsoStableNow,
                pathControlledNow = pathControlledNow,
                symmetricNow = symmetricNow,
                overRaisedNow = isOverRaisedNow
            )

            if (metrics.avgLiftAngle >= PEAK_LIFT_MIN) {
                peakReached = true
            }
            if (isOverRaisedNow) {
                overRaiseViolationDetected = true
            }
            if (cyclePeakTimeMs == 0L && metrics.avgLiftAngle >= PEAK_LIFT_MIN) {
                cyclePeakTimeMs = now
                if (cyclePeakTimeMs - cycleStartTimeMs < MIN_UP_PHASE_MS) {
                    tempoViolationDetected = true
                }
            }
        }

        // Lengan turun sebelum mencapai peak → batalkan siklus secara diam-diam (IDLE).
        // Evaluasi BENAR/SALAH hanya boleh muncul setelah repetisi penuh (peak tercapai + kembali ke bawah).
        // Konsisten dengan squat, bicep curl, dan shoulder press.
        if (cycleActive && !peakReached && metrics.avgLiftAngle <= READY_LIFT_MAX) {
            if (cycleMaxLiftAngle >= START_LIFT_MIN) {
                val finalFeedback = buildFeedback(
                    error = LateralRaiseFormError.RANGE_TOO_LOW,
                    defaultMessage = "Gerakan benar, tangan sejajar bahu dan tempo terkontrol"
                )
                resetCycleState(keepBottomReady = elbowBentAtReady)
                return RuleResult(
                    isValid = false,
                    feedback = finalFeedback,
                    primaryMetric = metrics.avgLiftAngle,
                    secondaryMetric = metrics.avgElbowAngle,
                    torsoAngle = metrics.torsoAngle,
                    liveFeedback = finalFeedback,
                    repStatus = BicepRepStatus.REP_BAD,
                    repCompleted = true,
                    shouldCountRep = false
                )
            }

            resetCycleState(keepBottomReady = elbowBentAtReady)
            return RuleResult(
                isValid = elbowBentAtReady,
                feedback = if (elbowBentAtReady) "Siap untuk repetisi berikutnya"
                           else "Jaga siku tetap sedikit menekuk",
                primaryMetric = metrics.avgLiftAngle,
                secondaryMetric = metrics.avgElbowAngle,
                torsoAngle = metrics.torsoAngle,
                liveFeedback = if (elbowBentAtReady) "Siap untuk repetisi berikutnya"
                               else "Jaga siku tetap sedikit menekuk",
                repStatus = BicepRepStatus.IDLE
            )
        }

        if (cycleActive && peakReached && hasReturnedToBottom(metrics)) {
            val keepBottomReady = metrics.avgLiftAngle <= READY_LIFT_MAX && elbowBentAtReady
            val completedViolation = finishCycle(now, keepBottomReady)
            val finalFeedback = buildFeedback(
                error = completedViolation,
                defaultMessage = "Gerakan benar, tangan sejajar bahu dan tempo terkontrol"
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

        val liveFeedback = when {
            !cycleActive && !elbowBentAtReady -> "Jaga siku tetap sedikit menekuk"
            cycleActive && !peakReached -> "Angkat lengan sampai sejajar bahu dengan kontrol"
            cycleActive && isOverRaisedNow -> "Turunkan lengan perlahan dengan kontrol"
            cycleActive -> "Turunkan lengan perlahan setelah sejajar bahu"
            else -> "Siap untuk repetisi berikutnya"
        }

        val liveFormValid = when {
            !cycleActive -> elbowBentAtReady
            metrics.avgLiftAngle < ACTIVE_LIFT_MIN -> true
            else -> shouldersRelaxedNow &&
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
            repStatus = currentRepStatus(),
            isPositionIssue = !cycleActive && !elbowBentAtReady
        )
    }

    override fun calculateMetric(pose: PoseResult): Float {
        return extractMetrics(pose.rawKeypoints)?.avgLiftAngle ?: 0f
    }

    override fun reset() {
        resetCycleState()
    }

    private fun resetCycleState(keepBottomReady: Boolean = false) {
        cycleActive = false
        cycleStartTimeMs = 0L
        cyclePeakTimeMs = 0L
        cycleLastSampleTimeMs = 0L
        readyFrames = if (keepBottomReady) READY_FRAMES else 0
        peakReached = false
        tempoViolationDetected = false
        overRaiseViolationDetected = false
        anchorShoulderY = 0f
        anchorHipCenterX = 0f
        anchorHipCenterY = 0f
        anchorTorsoAngle = 0f
        elbowLeadingViolationMs = 0L
        shrugViolationMs = 0L
        torsoViolationMs = 0L
        pathViolationMs = 0L
        symmetryViolationMs = 0L
        overRaiseViolationMs = 0L
        cycleMaxLiftAngle = 0f
        requireFreshBottomBeforeNextCycle = false
        activeMissingArmFrames = 0
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
        if (leftHip.confidence <= HIP_MIN_CONF || rightHip.confidence <= HIP_MIN_CONF) {
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
            requireFreshBottomBeforeNextCycle = false
        } else if (metrics.avgLiftAngle <= READY_LIFT_MAX) {
            readyFrames = (readyFrames - 1).coerceAtLeast(0)
        }
    }

    private fun canStartCycle(metrics: RepMetrics): Boolean {
        // Selalu butuh readyFrames — mencegah siklus terpicu oleh gerakan
        // tidak sengaja (mis. ayunan ringan saat berdiri diam).
        val hasReadyLatch = readyFrames >= READY_FRAMES ||
            (readyFrames > 0 && metrics.avgLiftAngle >= ACTIVE_LIFT_MIN)
        val clearBentLift = !requireFreshBottomBeforeNextCycle &&
            metrics.avgLiftAngle >= START_LIFT_MIN &&
            isBentEnough(metrics)
        return (hasReadyLatch || clearBentLift) && metrics.avgLiftAngle >= START_LIFT_MIN
    }

    private fun startCycle(now: Long, metrics: RepMetrics) {
        cycleActive = true
        cycleStartTimeMs = now
        cyclePeakTimeMs = 0L
        cycleLastSampleTimeMs = now
        peakReached = false
        tempoViolationDetected = false
        overRaiseViolationDetected = false
        anchorShoulderY = metrics.avgShoulderY
        anchorHipCenterX = metrics.hipCenterX
        anchorHipCenterY = metrics.hipCenterY
        anchorTorsoAngle = metrics.torsoAngle
        elbowLeadingViolationMs = 0L
        shrugViolationMs = 0L
        torsoViolationMs = 0L
        pathViolationMs = 0L
        symmetryViolationMs = 0L
        overRaiseViolationMs = 0L
        cycleMaxLiftAngle = metrics.avgLiftAngle
        readyFrames = 0
    }

    private fun accumulateViolations(
        dtMs: Long,
        elbowLeadingNow: Boolean,
        shouldersRelaxedNow: Boolean,
        torsoStableNow: Boolean,
        pathControlledNow: Boolean,
        symmetricNow: Boolean,
        overRaisedNow: Boolean
    ) {
        if (dtMs <= 0L) return

        if (!elbowLeadingNow) elbowLeadingViolationMs += dtMs
        if (!shouldersRelaxedNow) shrugViolationMs += dtMs
        if (!torsoStableNow) torsoViolationMs += dtMs
        if (!pathControlledNow) pathViolationMs += dtMs
        if (!symmetricNow) symmetryViolationMs += dtMs
        if (overRaisedNow) overRaiseViolationMs += dtMs
    }

    private fun finishCycle(now: Long, keepBottomReady: Boolean): LateralRaiseFormError? {
        val fullRepDuration = now - cycleStartTimeMs
        if (cyclePeakTimeMs > 0L && fullRepDuration < MIN_FULL_REP_MS) {
            tempoViolationDetected = true
        }

        val error = determineCompletedViolation(fullRepDuration)
        resetCycleState(keepBottomReady = keepBottomReady)
        requireFreshBottomBeforeNextCycle = !keepBottomReady
        return error
    }

    private fun cancelCycle() {
        resetCycleState()
    }

    private fun determineCompletedViolation(fullRepDurationMs: Long): LateralRaiseFormError? {
        if (!peakReached) {
            return LateralRaiseFormError.RANGE_TOO_LOW
        }
        if (overRaiseViolationDetected || hasSignificantViolation(overRaiseViolationMs, fullRepDurationMs)) {
            return LateralRaiseFormError.RANGE_TOO_HIGH
        }
        if (tempoViolationDetected) {
            return LateralRaiseFormError.TEMPO_TOO_FAST
        }
        return null
    }

    private fun hasSignificantViolation(durationMs: Long, fullRepDurationMs: Long): Boolean {
        if (durationMs >= VIOLATION_THRESHOLD_MS) return true
        if (fullRepDurationMs <= 0L) return false
        return durationMs.toFloat() / fullRepDurationMs.toFloat() >= VIOLATION_RATIO_THRESHOLD
    }

    private fun isBentEnough(metrics: RepMetrics): Boolean {
        return metrics.left.elbowAngle <= 165f &&
            metrics.right.elbowAngle <= 165f
    }

    private fun isElbowLeading(metrics: RepMetrics): Boolean {
        val leftLead = metrics.left.wrist.y - metrics.left.elbow.y
        val rightLead = metrics.right.wrist.y - metrics.right.elbow.y
        return leftLead >= ELBOW_LEAD_TOLERANCE && rightLead >= ELBOW_LEAD_TOLERANCE
    }

    private fun isRaisedTooHigh(metrics: RepMetrics): Boolean {
        val anyWristClearlyAbove = metrics.leftWristAboveShoulder || metrics.rightWristAboveShoulder
        val anyElbowClearlyAbove = metrics.leftElbowAboveShoulder || metrics.rightElbowAboveShoulder
        val forearmRaisedUpward =
            metrics.avgLiftAngle >= PEAK_LIFT_MIN &&
                (isWristClearlyAboveElbow(metrics.left) || isWristClearlyAboveElbow(metrics.right))
        return metrics.avgLiftAngle > OVER_RAISE_MAX ||
            metrics.avgLiftAngle > PEAK_LIFT_MAX ||
            anyWristClearlyAbove ||
            anyElbowClearlyAbove ||
            forearmRaisedUpward
    }

    private fun hasReturnedToBottom(metrics: RepMetrics): Boolean {
        return metrics.avgLiftAngle <= RETURN_LIFT_MAX ||
            (
                metrics.avgLiftAngle <= RETURN_LIFT_FALLBACK_MAX &&
                    cycleMaxLiftAngle >= PEAK_LIFT_MIN
                )
    }

    private fun isWristClearlyAboveElbow(arm: ArmMetrics): Boolean {
        return arm.wrist.y < arm.elbow.y - FOREARM_UPWARD_TOLERANCE
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
            LateralRaiseFormError.RANGE_TOO_LOW -> "Angkat lengan sampai sejajar bahu"
            LateralRaiseFormError.RANGE_TOO_HIGH -> "Jangan angkat tangan lebih tinggi dari bahu"
            LateralRaiseFormError.WRIST_LEADING -> "Pimpin gerakan dengan siku, jangan pergelangan tangan"
            LateralRaiseFormError.SHOULDER_SHRUG -> "Jangan angkat bahu saat mengangkat beban"
            LateralRaiseFormError.TEMPO_TOO_FAST -> "Tempo terlalu cepat, perlambat gerakan"
            LateralRaiseFormError.TORSO_SWAY -> "Jaga tubuh tetap tegak, jangan condong saat mengangkat"
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
