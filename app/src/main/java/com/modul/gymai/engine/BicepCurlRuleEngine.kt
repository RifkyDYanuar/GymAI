package com.modul.gymai.engine

import android.os.SystemClock
import com.modul.gymai.pose.Keypoint
import com.modul.gymai.pose.PoseResult
import com.modul.gymai.utils.AngleUtils
import kotlin.math.abs

class BicepCurlRuleEngine : ExerciseRuleEngine {

    companion object {
        // --- Confidence thresholds (dinaikkan untuk menekan jitter side-view) ---
        private const val MIN_CONF = 0.55f              // was 0.45 — elbow/wrist harus lebih yakin
        private const val MIN_SHOULDER_CONF = 0.45f     // was 0.35
        private const val CURL_START_THRESHOLD = 145f
        private const val ELBOW_PEAK_MIN = 35f
        private const val ELBOW_PEAK_MAX = 65f
        private const val ARM_EXTENDED_THRESHOLD = 155f
        private const val TORSO_STABILITY_THRESHOLD = 10f
        // --- Violation thresholds (dilonggarkan agar jitter sesaat tidak dihitung) ---
        private const val ELBOW_DRIFT_RATIO_THRESHOLD = 0.38f  // was 0.30 — toleransi lebih besar
        private const val UPPER_ARM_SWING_THRESHOLD = 28f      // was 22 — jitter tidak langsung trigger
        private const val MIN_UP_PHASE_MS = 450L
        private const val MIN_FULL_REP_MS = 900L
        private const val ARM_SWITCH_SCORE_MARGIN = 0.95f
        private const val ARM_SWITCH_CONFIRM_FRAMES = 8
        private const val ARM_LOST_SCORE_THRESHOLD = 0.95f
        private const val MAJOR_VIOLATION_THRESHOLD_MS = 450L  // was 250 — butuh >450ms baru dihitung
        private const val MAJOR_VIOLATION_RATIO_THRESHOLD = 0.30f
        private const val READY_EXTENSION_FRAMES = 4
        private const val START_ELBOW_FLEX_DELTA = 12f
        private const val START_WRIST_TRAVEL_RATIO_THRESHOLD = 0.18f
        private const val SIDE_VIEW_CONFIDENCE_MIN = 0.5f
        private const val SIDE_VIEW_SHOULDER_RATIO_MAX = 0.20f
        private const val SIDE_VIEW_HIP_RATIO_MAX = 0.14f
        private const val SIDE_VIEW_ABSOLUTE_SHOULDER_MAX = 0.10f
        // --- EMA smoothing untuk elbow (anti-jitter) ---
        private const val ELBOW_EMA_ALPHA = 0.35f  // 0=sangat halus, 1=tidak ada smoothing
        // --- Occlusion detection ---
        // Jika confidence elbow/wrist di bawah ini, tangan dianggap tertutup (occluded).
        // Keypoint tetap ada (17 titik utuh), tapi tidak dipakai untuk evaluasi.
        private const val RELIABLE_CONF = 0.65f
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
    private var readyExtensionFrames = 0
    private var readyReferenceWristX = 0f
    private var readyReferenceWristY = 0f
    private var readyArmSide: ArmSide? = null
    // EMA (Exponential Moving Average) untuk posisi elbow — meredam jitter
    private var smoothElbowX = -1f
    private var smoothElbowY = -1f
    private var smoothUpperArmAngle = -1f

    override fun validate(pose: PoseResult?): RuleResult {
        if (pose == null) {
            cancelCycle()
            return RuleResult(
                isValid = false,
                feedback = "Pastikan seluruh tubuh terlihat di kamera",
                liveFeedback = "Pastikan seluruh tubuh terlihat di kamera",
                repStatus = currentRepStatus(),
                isPositionIssue = true
            )
        }

        val kp = pose.rawKeypoints
        if (!pose.isValid() && !isBicepPoseUsable(kp)) {
            cancelCycle()
            return RuleResult(
                isValid = false,
                feedback = "Pastikan seluruh tubuh terlihat di kamera",
                liveFeedback = "Pastikan seluruh tubuh terlihat di kamera",
                repStatus = currentRepStatus(),
                isPositionIssue = true
            )
        }

        val now = SystemClock.elapsedRealtime()
        if (!isSideViewPose(kp)) {
            cancelCycle()
            return RuleResult(
                isValid = false,
                feedback = "Harus menghadap ke samping",
                liveFeedback = "Harus menghadap ke samping",
                repStatus = currentRepStatus(),
                isPositionIssue = true
            )
        }

        val trackedArm = selectTrackingArm(kp) ?: run {
            cancelCycle()
            return RuleResult(
                isValid = false,
                feedback = "Pastikan seluruh tubuh terlihat di kamera",
                liveFeedback = "Pastikan seluruh tubuh terlihat di kamera",
                repStatus = currentRepStatus(),
                isPositionIssue = true
            )
        }
        lastResolvedArmSide = trackedArm.side

        val shoulder = trackedArm.shoulder
        val elbow = trackedArm.elbow
        val wrist = trackedArm.wrist

        // --- Deteksi oklusi: apakah tangan yang di-track sedang tertutup? ---
        // Oklusi terjadi saat menghadap samping dan tangan sisi jauh terhalang tubuh.
        // Keypoint tetap ADA (17 titik utuh di PoseResult), hanya evaluasi yang di-skip.
        val isArmOccluded = elbow.confidence < RELIABLE_CONF || wrist.confidence < RELIABLE_CONF

        // EMA smoothing: hanya update saat tangan terlihat jelas.
        // Saat tertutup, nilai smooth di-freeze supaya angle tidak melompat akibat jitter.
        if (!isArmOccluded) {
            smoothElbowX = if (smoothElbowX < 0f) elbow.x else smoothElbowX + ELBOW_EMA_ALPHA * (elbow.x - smoothElbowX)
            smoothElbowY = if (smoothElbowY < 0f) elbow.y else smoothElbowY + ELBOW_EMA_ALPHA * (elbow.y - smoothElbowY)
            val rawUpperArmAngle = AngleUtils.verticalAngle(elbow.x, elbow.y, shoulder.x, shoulder.y)
            smoothUpperArmAngle = if (smoothUpperArmAngle < 0f) rawUpperArmAngle
                else smoothUpperArmAngle + ELBOW_EMA_ALPHA * (rawUpperArmAngle - smoothUpperArmAngle)
        }
        // Fallback: jika smooth belum pernah diisi (sesi baru), gunakan posisi raw
        val effectiveElbowX = if (smoothElbowX >= 0f) smoothElbowX else elbow.x
        val effectiveElbowY = if (smoothElbowY >= 0f) smoothElbowY else elbow.y
        val effectiveUpperArmAngle = if (smoothUpperArmAngle >= 0f) smoothUpperArmAngle
            else AngleUtils.verticalAngle(elbow.x, elbow.y, shoulder.x, shoulder.y)

        // Hitung angle menggunakan posisi yang sudah dihaluskan (atau di-freeze)
        val elbowAngle = AngleUtils.angleBetween(
            shoulder.x, shoulder.y, effectiveElbowX, effectiveElbowY, wrist.x, wrist.y
        )
        val upperArmAngle = effectiveUpperArmAngle

        updateReadyState(trackedArm.side, elbowAngle, wrist)

        val lHip = kp[Keypoint.LEFT_HIP]; val rHip = kp[Keypoint.RIGHT_HIP]
        val torsoAngle = if (lHip.confidence > MIN_CONF && rHip.confidence > MIN_CONF) {
            val midShoulderX = (kp[Keypoint.LEFT_SHOULDER].x + kp[Keypoint.RIGHT_SHOULDER].x) / 2f
            val midShoulderY = (kp[Keypoint.LEFT_SHOULDER].y + kp[Keypoint.RIGHT_SHOULDER].y) / 2f
            val midHipX = (lHip.x + rHip.x) / 2f
            val midHipY = (lHip.y + rHip.y) / 2f
            AngleUtils.verticalAngle(midHipX, midHipY, midShoulderX, midShoulderY)
        } else 0f

        if (!cycleActive && canStartCycle(shoulder, elbow, wrist, elbowAngle)) {
            startCycle(now, elbow, upperArmAngle)
        }

        val isTorsoStable = torsoAngle <= TORSO_STABILITY_THRESHOLD
        val elbowDrift = if (cycleActive && !isArmOccluded) {
            // Gunakan posisi smooth untuk menghitung drift dari anchor
            val upperArmLength = AngleUtils.distance(shoulder.x, shoulder.y, effectiveElbowX, effectiveElbowY).coerceAtLeast(0.001f)
            AngleUtils.distance(anchorElbowX, anchorElbowY, effectiveElbowX, effectiveElbowY) / upperArmLength
        } else 0f

        // Saat tangan tertutup (isArmOccluded): skip cek violation — jangan akumulasi waktu pelanggaran
        // karena keypoint tidak reliable, bukan berarti gerakan salah.
        val elbowMovingNow = cycleActive && !isArmOccluded && (
            elbowDrift > ELBOW_DRIFT_RATIO_THRESHOLD ||
                abs(upperArmAngle - anchorUpperArmAngle) > UPPER_ARM_SWING_THRESHOLD
            )
        val torsoMovingNow = cycleActive && !isArmOccluded && !isTorsoStable

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
                defaultMessage = "Gerakan benar, siku tetap stabil dan fleksi siku optimal"
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
            isArmOccluded && cycleActive -> "Tahan posisi, lanjutkan gerakan"
            tempoViolationDetected -> "Tempo terlalu cepat, perlambat gerakan"
            elbowMovingNow -> "Jaga siku tetap diam di samping tubuh"
            torsoMovingNow -> "Jaga tubuh tetap tegak dan hindari ayunan badan"
            cycleActive && elbowAngle < 90f && elbowAngle > ELBOW_PEAK_MAX -> "Angkat beban lebih tinggi hingga siku menekuk optimal"
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

    private fun isSideViewPose(keypoints: List<Keypoint>): Boolean {
        val leftShoulder = keypoints[Keypoint.LEFT_SHOULDER]
        val rightShoulder = keypoints[Keypoint.RIGHT_SHOULDER]
        val leftShoulderVisible = leftShoulder.confidence > SIDE_VIEW_CONFIDENCE_MIN
        val rightShoulderVisible = rightShoulder.confidence > SIDE_VIEW_CONFIDENCE_MIN
        if (
            leftShoulderVisible.xor(rightShoulderVisible) &&
            isBicepPoseUsable(keypoints)
        ) {
            return true
        }
        if (!leftShoulderVisible || !rightShoulderVisible) {
            return false
        }

        val shoulderWidth = abs(leftShoulder.x - rightShoulder.x)
        val bodyTop = minOf(leftShoulder.y, rightShoulder.y)
        val lowerBodyPoints = listOf(
            keypoints[Keypoint.LEFT_HIP],
            keypoints[Keypoint.RIGHT_HIP],
            keypoints[Keypoint.LEFT_KNEE],
            keypoints[Keypoint.RIGHT_KNEE],
            keypoints[Keypoint.LEFT_ANKLE],
            keypoints[Keypoint.RIGHT_ANKLE]
        ).filter { it.confidence > 0.35f }

        val bodyHeight = lowerBodyPoints
            .maxOfOrNull { it.y }
            ?.minus(bodyTop)
            ?.coerceAtLeast(0.01f)

        if (bodyHeight == null || bodyHeight < 0.18f) {
            return shoulderWidth <= SIDE_VIEW_ABSOLUTE_SHOULDER_MAX
        }

        val shoulderRatio = shoulderWidth / bodyHeight
        val leftHip = keypoints[Keypoint.LEFT_HIP]
        val rightHip = keypoints[Keypoint.RIGHT_HIP]
        val hipAccepted = if (
            leftHip.confidence > SIDE_VIEW_CONFIDENCE_MIN &&
            rightHip.confidence > SIDE_VIEW_CONFIDENCE_MIN
        ) {
            abs(leftHip.x - rightHip.x) / bodyHeight <= SIDE_VIEW_HIP_RATIO_MAX
        } else {
            true
        }

        return shoulderRatio <= SIDE_VIEW_SHOULDER_RATIO_MAX && hipAccepted
    }

    private fun isBicepPoseUsable(keypoints: List<Keypoint>): Boolean {
        val leftArm = getArmCandidate(keypoints, ArmSide.LEFT) != null
        val rightArm = getArmCandidate(keypoints, ArmSide.RIGHT) != null
        if (!leftArm && !rightArm) return false

        // Sengaja tidak memasukkan ankle karena saat side-view, ML Kit sering
        // salah mendeteksi posisi ankle (terangkat / jitter) yang justru memperburuk
        // validasi. Hip dan knee sudah cukup untuk memastikan tubuh terlihat.
        val visibleBodyPoints = listOf(
            Keypoint.NOSE,
            Keypoint.LEFT_SHOULDER,
            Keypoint.RIGHT_SHOULDER,
            Keypoint.LEFT_HIP,
            Keypoint.RIGHT_HIP,
            Keypoint.LEFT_KNEE,
            Keypoint.RIGHT_KNEE
        ).count { index ->
            keypoints.getOrNull(index)?.confidence ?: 0f > 0.35f
        }

        return visibleBodyPoints >= 4
    }

    private fun startCycle(now: Long, elbow: Keypoint, upperArmAngle: Float) {
        cycleActive = true
        cycleStartTimeMs = now
        cyclePeakTimeMs = 0L
        cycleLastSampleTimeMs = now
        // Gunakan posisi EMA (smooth) sebagai anchor agar tidak dari posisi jitter
        anchorElbowX = smoothElbowX.takeIf { it >= 0f } ?: elbow.x
        anchorElbowY = smoothElbowY.takeIf { it >= 0f } ?: elbow.y
        anchorUpperArmAngle = smoothUpperArmAngle.takeIf { it >= 0f } ?: upperArmAngle
        totalMajorViolationMs = 0L
        elbowViolationMs = 0L
        torsoViolationMs = 0L
        peakReached = false
        tempoViolationDetected = false
        resetReadyState()
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
        anchorElbowX = smoothElbowX.takeIf { it >= 0f } ?: elbow.x
        anchorElbowY = smoothElbowY.takeIf { it >= 0f } ?: elbow.y
        anchorUpperArmAngle = smoothUpperArmAngle.takeIf { it >= 0f } ?: upperArmAngle
        totalMajorViolationMs = 0L
        elbowViolationMs = 0L
        torsoViolationMs = 0L
        peakReached = false
        tempoViolationDetected = false
        resetReadyState()

        return completedViolation
    }

    private fun updateReadyState(side: ArmSide, elbowAngle: Float, wrist: Keypoint) {
        if (cycleActive) return

        if (readyArmSide != side) {
            resetReadyState()
            readyArmSide = side
        }

        if (elbowAngle >= ARM_EXTENDED_THRESHOLD) {
            readyExtensionFrames = (readyExtensionFrames + 1).coerceAtMost(READY_EXTENSION_FRAMES + 2)
            readyReferenceWristX = wrist.x
            readyReferenceWristY = wrist.y
        }
    }

    private fun canStartCycle(
        shoulder: Keypoint,
        elbow: Keypoint,
        wrist: Keypoint,
        elbowAngle: Float
    ): Boolean {
        if (readyExtensionFrames < READY_EXTENSION_FRAMES) return false
        if (elbowAngle >= CURL_START_THRESHOLD) return false

        val upperArmLength = AngleUtils.distance(shoulder.x, shoulder.y, elbow.x, elbow.y).coerceAtLeast(0.001f)
        val wristTravelRatio =
            AngleUtils.distance(readyReferenceWristX, readyReferenceWristY, wrist.x, wrist.y) / upperArmLength
        val elbowFlexDelta = ARM_EXTENDED_THRESHOLD - elbowAngle

        return elbowFlexDelta >= START_ELBOW_FLEX_DELTA &&
            wristTravelRatio >= START_WRIST_TRAVEL_RATIO_THRESHOLD
    }

    private fun cancelCycle() {
        cycleActive = false
        cycleStartTimeMs = 0L
        cyclePeakTimeMs = 0L
        cycleLastSampleTimeMs = 0L
        totalMajorViolationMs = 0L
        elbowViolationMs = 0L
        torsoViolationMs = 0L
        peakReached = false
        tempoViolationDetected = false
        resetReadyState()
    }

    private fun resetReadyState() {
        readyExtensionFrames = 0
        readyReferenceWristX = 0f
        readyReferenceWristY = 0f
        readyArmSide = null
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
            error == BicepFormError.TORSO_SWAY -> "Jaga tubuh tetap tegak dan hindari ayunan badan"
            error == BicepFormError.RANGE_INCOMPLETE -> "Angkat beban lebih tinggi hingga siku menekuk optimal"
            elbowAngle < 90f && elbowAngle !in ELBOW_PEAK_MIN..ELBOW_PEAK_MAX -> "Angkat beban lebih tinggi hingga siku menekuk optimal"
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
        // Reset EMA state
        smoothElbowX = -1f
        smoothElbowY = -1f
        smoothUpperArmAngle = -1f
        resetReadyState()
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
