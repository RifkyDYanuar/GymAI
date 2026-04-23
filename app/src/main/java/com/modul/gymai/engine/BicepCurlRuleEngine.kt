package com.modul.gymai.engine

import android.os.SystemClock
import com.modul.gymai.pose.Keypoint
import com.modul.gymai.pose.PoseResult
import com.modul.gymai.utils.AngleUtils
import kotlin.math.abs

class BicepCurlRuleEngine : ExerciseRuleEngine {

    companion object {
        private const val MIN_CONF = 0.45f
        private const val MIN_SHOULDER_CONF = 0.35f
        private const val CURL_START_THRESHOLD = 145f
        private const val ELBOW_PEAK_MIN = 35f
        private const val ELBOW_PEAK_MAX = 70f
        private const val ARM_EXTENDED_THRESHOLD = 155f
        private const val TORSO_STABILITY_THRESHOLD = 15f
        private const val ELBOW_DRIFT_RATIO_THRESHOLD = 0.30f
        private const val UPPER_ARM_SWING_THRESHOLD = 22f
        private const val MIN_UP_PHASE_MS = 450L
        private const val MIN_FULL_REP_MS = 900L
        private const val ARM_SWITCH_SCORE_MARGIN = 0.55f
        private const val ARM_SWITCH_CONFIRM_FRAMES = 4
        private const val ARM_LOST_SCORE_THRESHOLD = 1.1f
        private const val MAJOR_VIOLATION_THRESHOLD_MS = 250L
        private const val MAJOR_VIOLATION_RATIO_THRESHOLD = 0.30f
    }

    private var cycleActive = false
    private var cycleStartTimeMs = 0L
    private var cyclePeakTimeMs = 0L
    private var cycleLastSampleTimeMs = 0L
    private var anchorElbowX = 0f
    private var anchorElbowY = 0f
    private var anchorUpperArmAngle = 0f
    private var totalMajorViolationMs = 0L
    private var elbowViolationMs = 0L
    private var torsoViolationMs = 0L
    private var peakReached = false
    private var tempoViolationDetected = false
    private var trackedArmSide: ArmSide? = null
    private var pendingArmSide: ArmSide? = null
    private var pendingArmFrames = 0
    private var lastResolvedArmSide: ArmSide? = null

    override fun validate(pose: PoseResult?): RuleResult {
        if (pose == null || !pose.isValid()) {
            return RuleResult(
                isValid = false,
                feedback = "Pastikan tubuh terlihat jelas di kamera",
                liveFeedback = "Pastikan tubuh terlihat jelas di kamera",
                repStatus = currentRepStatus()
            )
        }

        val now = SystemClock.elapsedRealtime()
        val kp = pose.rawKeypoints
        val lShoulder = kp[Keypoint.LEFT_SHOULDER]
        val rShoulder = kp[Keypoint.RIGHT_SHOULDER]

        val shoulderDist = Math.abs(lShoulder.x - rShoulder.x)
        if (lShoulder.confidence > 0.5f && rShoulder.confidence > 0.5f && shoulderDist > 0.15f) {
            return RuleResult(
                isValid = false,
                feedback = "Harus menghadap ke samping",
                liveFeedback = "Harus menghadap ke samping",
                repStatus = currentRepStatus()
            )
        }

        val trackedArm = selectTrackingArm(kp) ?: run {
            return RuleResult(
                isValid = false,
                feedback = "Lengan tidak terdeteksi",
                liveFeedback = "Lengan tidak terdeteksi",
                repStatus = currentRepStatus()
            )
        }
        lastResolvedArmSide = trackedArm.side

        val shoulder = trackedArm.shoulder
        val elbow = trackedArm.elbow
        val wrist = trackedArm.wrist

        val elbowAngle = AngleUtils.angleBetween(shoulder.x, shoulder.y, elbow.x, elbow.y, wrist.x, wrist.y)
        val upperArmAngle = AngleUtils.verticalAngle(elbow.x, elbow.y, shoulder.x, shoulder.y)

        val lHip = kp[Keypoint.LEFT_HIP]; val rHip = kp[Keypoint.RIGHT_HIP]
        val torsoAngle = if (lHip.confidence > MIN_CONF && rHip.confidence > MIN_CONF) {
            val midShoulderX = (kp[Keypoint.LEFT_SHOULDER].x + kp[Keypoint.RIGHT_SHOULDER].x) / 2f
            val midShoulderY = (kp[Keypoint.LEFT_SHOULDER].y + kp[Keypoint.RIGHT_SHOULDER].y) / 2f
            val midHipX = (lHip.x + rHip.x) / 2f
            val midHipY = (lHip.y + rHip.y) / 2f
            AngleUtils.verticalAngle(midHipX, midHipY, midShoulderX, midShoulderY)
        } else 0f

        if (!cycleActive && elbowAngle < CURL_START_THRESHOLD) {
            startCycle(now, elbow, upperArmAngle)
        }

        val isTorsoStable = torsoAngle <= TORSO_STABILITY_THRESHOLD
        val elbowDrift = if (cycleActive) {
            val upperArmLength = AngleUtils.distance(shoulder.x, shoulder.y, elbow.x, elbow.y).coerceAtLeast(0.001f)
            AngleUtils.distance(anchorElbowX, anchorElbowY, elbow.x, elbow.y) / upperArmLength
        } else 0f

        val elbowMovingNow = cycleActive && (
            elbowDrift > ELBOW_DRIFT_RATIO_THRESHOLD ||
                abs(upperArmAngle - anchorUpperArmAngle) > UPPER_ARM_SWING_THRESHOLD
            )
        val torsoMovingNow = cycleActive && !isTorsoStable

        if (cycleActive) {
            val dtMs = (now - cycleLastSampleTimeMs).coerceAtLeast(0L)
            cycleLastSampleTimeMs = now
            accumulateViolationDurations(dtMs, elbowMovingNow, torsoMovingNow)

            if (elbowAngle <= ELBOW_PEAK_MAX) {
                peakReached = true
            }

            if (cyclePeakTimeMs == 0L && elbowAngle <= ELBOW_PEAK_MAX) {
                cyclePeakTimeMs = now
                val upPhaseDuration = cyclePeakTimeMs - cycleStartTimeMs
                if (upPhaseDuration < MIN_UP_PHASE_MS) {
                    tempoViolationDetected = true
                }
            }
        }

        if (cycleActive && elbowAngle > ARM_EXTENDED_THRESHOLD) {
            val completedViolation = finishCycle(now, elbow, upperArmAngle)
            val finalFeedback = buildFeedback(
                error = completedViolation,
                elbowAngle = elbowAngle,
                defaultMessage = "Repetisi bagus, lanjutkan dengan kontrol"
            )
            val repStatus = if (completedViolation == null) BicepRepStatus.REP_GOOD else BicepRepStatus.REP_BAD
            val shouldCountRep = completedViolation == null
            return RuleResult(
                isValid = shouldCountRep,
                feedback = finalFeedback,
                primaryMetric = elbowAngle,
                secondaryMetric = elbowDrift,
                torsoAngle = torsoAngle,
                liveFeedback = finalFeedback,
                repStatus = repStatus,
                repCompleted = true,
                shouldCountRep = shouldCountRep
            )
        }

        val liveFeedback = when {
            tempoViolationDetected -> "Tempo terlalu cepat, perlambat gerakan"
            elbowMovingNow -> "Jaga siku tetap diam di samping tubuh"
            torsoMovingNow -> "Jaga tubuh tetap tegak, jangan terlalu bergoyang"
            cycleActive && elbowAngle < 90f && elbowAngle > ELBOW_PEAK_MAX -> "Angkat beban sedikit lebih tinggi"
            cycleActive && elbowAngle >= 90f -> "Lakukan gerakan curl dengan kontrol"
            cycleActive -> "Gerakan curl baik, lanjutkan dengan kontrol"
            else -> "Siap untuk repetisi berikutnya"
        }

        return RuleResult(
            isValid = true,
            feedback = liveFeedback,
            primaryMetric = elbowAngle,
            secondaryMetric = elbowDrift,
            torsoAngle = torsoAngle,
            liveFeedback = liveFeedback,
            repStatus = currentRepStatus()
        )
    }

    private fun startCycle(now: Long, elbow: Keypoint, upperArmAngle: Float) {
        cycleActive = true
        cycleStartTimeMs = now
        cyclePeakTimeMs = 0L
        cycleLastSampleTimeMs = now
        anchorElbowX = elbow.x
        anchorElbowY = elbow.y
        anchorUpperArmAngle = upperArmAngle
        totalMajorViolationMs = 0L
        elbowViolationMs = 0L
        torsoViolationMs = 0L
        peakReached = false
        tempoViolationDetected = false
    }

    private fun accumulateViolationDurations(dtMs: Long, elbowMovingNow: Boolean, torsoMovingNow: Boolean) {
        if (dtMs <= 0L) return

        if (elbowMovingNow || torsoMovingNow) {
            totalMajorViolationMs += dtMs
        }

        if (elbowMovingNow) {
            elbowViolationMs += dtMs
        }

        if (torsoMovingNow) {
            torsoViolationMs += dtMs
        }
    }

    private fun finishCycle(now: Long, elbow: Keypoint, upperArmAngle: Float): BicepFormError? {
        val fullRepDuration = now - cycleStartTimeMs
        if (cyclePeakTimeMs > 0L && fullRepDuration < MIN_FULL_REP_MS) {
            tempoViolationDetected = true
        }

        val completedViolation = determineCompletedViolation(fullRepDuration)

        cycleActive = false
        cycleStartTimeMs = 0L
        cyclePeakTimeMs = 0L
        cycleLastSampleTimeMs = 0L
        anchorElbowX = elbow.x
        anchorElbowY = elbow.y
        anchorUpperArmAngle = upperArmAngle
        totalMajorViolationMs = 0L
        elbowViolationMs = 0L
        torsoViolationMs = 0L
        peakReached = false
        tempoViolationDetected = false

        return completedViolation
    }

    private fun determineCompletedViolation(fullRepDurationMs: Long): BicepFormError? {
        if (tempoViolationDetected) {
            return BicepFormError.TEMPO_TOO_FAST
        }

        if (!peakReached) {
            return BicepFormError.RANGE_INCOMPLETE
        }

        val majorViolationRatio = if (fullRepDurationMs > 0L) {
            totalMajorViolationMs.toFloat() / fullRepDurationMs.toFloat()
        } else {
            0f
        }

        if (totalMajorViolationMs >= MAJOR_VIOLATION_THRESHOLD_MS || majorViolationRatio >= MAJOR_VIOLATION_RATIO_THRESHOLD) {
            return if (elbowViolationMs >= torsoViolationMs) {
                BicepFormError.ELBOW_MOVING
            } else {
                BicepFormError.TORSO_SWAY
            }
        }

        return null
    }

    private fun buildFeedback(error: BicepFormError?, elbowAngle: Float, defaultMessage: String): String {
        return when {
            error == BicepFormError.TEMPO_TOO_FAST -> "Tempo terlalu cepat, perlambat gerakan"
            error == BicepFormError.ELBOW_MOVING -> "Jaga siku tetap diam di samping tubuh"
            error == BicepFormError.TORSO_SWAY -> "Jaga tubuh tetap tegak, jangan terlalu bergoyang"
            error == BicepFormError.RANGE_INCOMPLETE -> "Angkat beban sedikit lebih tinggi"
            elbowAngle < 90f && elbowAngle !in ELBOW_PEAK_MIN..ELBOW_PEAK_MAX -> "Angkat beban sedikit lebih tinggi"
            else -> defaultMessage
        }
    }

    private fun currentRepStatus(): BicepRepStatus {
        return if (cycleActive) BicepRepStatus.IN_PROGRESS else BicepRepStatus.IDLE
    }

    override fun calculateMetric(pose: PoseResult): Float {
        val kp = pose.rawKeypoints
        val trackedArm = lastResolvedArmSide?.let { getArmCandidate(kp, it) } ?: selectTrackingArm(kp) ?: return 180f
        return AngleUtils.angleBetween(
            trackedArm.shoulder.x, trackedArm.shoulder.y,
            trackedArm.elbow.x, trackedArm.elbow.y,
            trackedArm.wrist.x, trackedArm.wrist.y
        )
    }

    private fun selectTrackingArm(kp: List<Keypoint>): ArmCandidate? {
        val leftArm = getArmCandidate(kp, ArmSide.LEFT)
        val rightArm = getArmCandidate(kp, ArmSide.RIGHT)
        val currentArm = trackedArmSide?.let { side ->
            when (side) {
                ArmSide.LEFT -> leftArm
                ArmSide.RIGHT -> rightArm
            }
        }

        if (cycleActive && currentArm != null) {
            return currentArm
        }

        if (cycleActive && currentArm == null) {
            val fallback = preferredArm(leftArm, rightArm) ?: return null
            trackedArmSide = fallback.side
            resetPendingArmSwitch()
            return fallback
        }

        if (currentArm != null && currentArm.score >= ARM_LOST_SCORE_THRESHOLD) {
            val challenger = otherArm(currentArm.side, leftArm, rightArm)
            if (challenger != null && challenger.score > currentArm.score + ARM_SWITCH_SCORE_MARGIN) {
                confirmArmSwitch(challenger.side)
            } else {
                resetPendingArmSwitch()
            }
            return currentArm
        }

        val preferred = preferredArm(leftArm, rightArm) ?: run {
            trackedArmSide = null
            resetPendingArmSwitch()
            return null
        }

        if (trackedArmSide == preferred.side) {
            resetPendingArmSwitch()
            return preferred
        }

        confirmArmSwitch(preferred.side)
        return when {
            trackedArmSide == preferred.side -> preferred
            currentArm != null -> currentArm
            else -> preferred
        }
    }

    private fun confirmArmSwitch(targetSide: ArmSide) {
        if (pendingArmSide == targetSide) {
            pendingArmFrames++
        } else {
            pendingArmSide = targetSide
            pendingArmFrames = 1
        }

        if (pendingArmFrames >= ARM_SWITCH_CONFIRM_FRAMES) {
            trackedArmSide = targetSide
            resetPendingArmSwitch()
        }
    }

    private fun resetPendingArmSwitch() {
        pendingArmSide = null
        pendingArmFrames = 0
    }

    private fun preferredArm(leftArm: ArmCandidate?, rightArm: ArmCandidate?): ArmCandidate? {
        return when {
            leftArm == null -> rightArm
            rightArm == null -> leftArm
            rightArm.score > leftArm.score -> rightArm
            else -> leftArm
        }
    }

    private fun otherArm(currentSide: ArmSide, leftArm: ArmCandidate?, rightArm: ArmCandidate?): ArmCandidate? {
        return when (currentSide) {
            ArmSide.LEFT -> rightArm
            ArmSide.RIGHT -> leftArm
        }
    }

    private fun getArmCandidate(kp: List<Keypoint>, side: ArmSide): ArmCandidate? {
        val shoulderIndex = if (side == ArmSide.LEFT) Keypoint.LEFT_SHOULDER else Keypoint.RIGHT_SHOULDER
        val elbowIndex = if (side == ArmSide.LEFT) Keypoint.LEFT_ELBOW else Keypoint.RIGHT_ELBOW
        val wristIndex = if (side == ArmSide.LEFT) Keypoint.LEFT_WRIST else Keypoint.RIGHT_WRIST

        val shoulder = kp[shoulderIndex]
        val elbow = kp[elbowIndex]
        val wrist = kp[wristIndex]

        if (shoulder.confidence < MIN_SHOULDER_CONF || elbow.confidence < MIN_CONF || wrist.confidence < MIN_CONF) {
            return null
        }

        val upperArmLength = AngleUtils.distance(shoulder.x, shoulder.y, elbow.x, elbow.y)
        val forearmLength = AngleUtils.distance(elbow.x, elbow.y, wrist.x, wrist.y)
        if (upperArmLength < 0.03f || forearmLength < 0.03f) {
            return null
        }

        val score =
            (shoulder.confidence * 0.9f) +
            (elbow.confidence * 1.15f) +
            (wrist.confidence * 1.15f)

        return ArmCandidate(side, shoulder, elbow, wrist, score)
    }

    override fun reset() {
        cycleActive = false
        cycleStartTimeMs = 0L
        cyclePeakTimeMs = 0L
        cycleLastSampleTimeMs = 0L
        anchorElbowX = 0f
        anchorElbowY = 0f
        anchorUpperArmAngle = 0f
        totalMajorViolationMs = 0L
        elbowViolationMs = 0L
        torsoViolationMs = 0L
        peakReached = false
        tempoViolationDetected = false
        trackedArmSide = null
        pendingArmSide = null
        pendingArmFrames = 0
        lastResolvedArmSide = null
    }

    private data class ArmCandidate(
        val side: ArmSide,
        val shoulder: Keypoint,
        val elbow: Keypoint,
        val wrist: Keypoint,
        val score: Float
    )

    private enum class ArmSide {
        LEFT,
        RIGHT
    }

    private enum class BicepFormError {
        BODY_NOT_VISIBLE,
        NOT_SIDE_VIEW,
        ARM_NOT_VISIBLE,
        ELBOW_MOVING,
        TORSO_SWAY,
        TEMPO_TOO_FAST,
        RANGE_INCOMPLETE
    }
}
