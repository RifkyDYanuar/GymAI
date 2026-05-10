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
        private const val FRONT_VIEW_SHOULDER_DISTANCE_MIN = 0.10f
        private const val FRONT_VIEW_HIP_DISTANCE_MIN = 0.06f
        private const val READY_FRAMES = 4
        private const val READY_ELBOW_MIN = 65f
        private const val READY_ELBOW_MAX = 125f
        private const val READY_ELBOW_BELOW_SHOULDER_MAX = 0.13f
        private const val READY_WRIST_BELOW_SHOULDER_MAX = 0.18f
        private const val START_ELBOW_MIN = 126f
        private const val ELBOW_PEAK_MIN = 150f
        private const val ELBOW_PEAK_MAX = 167f
        private const val ELBOW_LOCKOUT_MAX = 168f
        private const val ARM_TORSO_PEAK_MIN = 145f
        private const val ARM_TORSO_PEAK_MAX = 180f
        private const val RETURN_ELBOW_MAX = 118f
        private const val TORSO_STABILITY_THRESHOLD = 10f
        private const val ELBOW_SYMMETRY_THRESHOLD = 18f
        private const val ARM_TORSO_SYMMETRY_THRESHOLD = 18f
        private const val WRIST_HEIGHT_SYMMETRY_THRESHOLD = 0.08f
        private const val MIN_UP_PHASE_MS = 200L
        private const val MIN_FULL_REP_MS = 550L
        private const val VIOLATION_THRESHOLD_MS = 220L
        private const val VIOLATION_RATIO_THRESHOLD = 0.24f
        private const val RETURN_HOLD_MIN_MS = 1_000L
        private const val RETURN_ELBOW_BELOW_SHOULDER_MAX = 0.10f
        private const val ELBOW_DROP_TORSO_RATIO = 0.34f
    }

    private var cycleActive = false
    private var cycleStartTimeMs = 0L
    private var cyclePeakTimeMs = 0L
    private var cycleLastSampleTimeMs = 0L
    private var readyFrames = 0
    private var peakReached = false
    private var tempoViolationDetected = false
    private var elbowLockoutViolationDetected = false
    private var elbowDropViolationDetected = false
    private var lastCompletedWithElbowDrop = false
    private var anchorTorsoAngle = 0f
    private var torsoViolationMs = 0L
    private var symmetryViolationMs = 0L
    private var cycleMaxElbowAngle = 0f
    private var returnHoldStartTimeMs = 0L
    // Grace period setelah rep selesai: jangan tampilkan pesan posisi awal
    // saat tangan sedang dalam transisi turun kembali ke starting position
    private var postRepGraceFrames = 0

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
                feedback = "Pastikan kedua lengan terlihat jelas",
                liveFeedback = "Pastikan kedua lengan terlihat jelas",
                repStatus = currentRepStatus(),
                isPositionIssue = true
            )
        }

        if (!isFacingFront(metrics)) {
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

        // Kurangi grace period per frame
        if (postRepGraceFrames > 0) postRepGraceFrames--

        if (!cycleActive && lastCompletedWithElbowDrop) {
            if (isElbowDroppedTooLow(metrics)) {
                val feedback = buildFeedback(ShoulderPressFormError.ELBOW_DROP, "Siap untuk repetisi berikutnya")
                return RuleResult(
                    isValid = false,
                    feedback = feedback,
                    primaryMetric = metrics.avgElbowAngle,
                    secondaryMetric = metrics.avgArmTorsoAngle,
                    torsoAngle = metrics.torsoAngle,
                    liveFeedback = feedback,
                    repStatus = BicepRepStatus.REP_BAD,
                    repCompleted = false,
                    shouldCountRep = false,
                    isPositionIssue = false
                )
            }
            lastCompletedWithElbowDrop = false
        }

        if (!cycleActive && postRepGraceFrames == 0 && !isReadyStartPosition(metrics)) {
            return RuleResult(
                isValid = false,
                feedback = "Letakkan dumbel di atas bahu",
                primaryMetric = metrics.avgElbowAngle,
                secondaryMetric = metrics.avgArmTorsoAngle,
                torsoAngle = metrics.torsoAngle,
                liveFeedback = "Letakkan dumbel di atas bahu",
                repStatus = BicepRepStatus.IDLE,
                repCompleted = false,
                shouldCountRep = false,
                isPositionIssue = true
            )
        }

        val torsoStableNow = isTorsoStable(metrics)
        val symmetricNow = isSymmetric(metrics)
        val topRangeReachedNow = isTopRange(metrics)
        val overheadReachedNow = topRangeReachedNow || isOverheadLockout(metrics)

        if (cycleActive) {
            val dtMs = (now - cycleLastSampleTimeMs).coerceAtLeast(0L)
            cycleLastSampleTimeMs = now
            cycleMaxElbowAngle = maxOf(cycleMaxElbowAngle, metrics.avgElbowAngle)
            accumulateViolations(dtMs, torsoStableNow, symmetricNow)

            if (now > cycleStartTimeMs && overheadReachedNow) {
                peakReached = true
            }
            if (isElbowLockedOut(metrics)) {
                elbowLockoutViolationDetected = true
            }
            if (cyclePeakTimeMs == 0L && now > cycleStartTimeMs && overheadReachedNow) {
                cyclePeakTimeMs = now
                if (cyclePeakTimeMs - cycleStartTimeMs < MIN_UP_PHASE_MS) {
                    tempoViolationDetected = true
                }
            }
            // Deteksi siku jatuh terlalu rendah — hanya dicheck setelah peak tercapai
            // (fase turun kembali ke bawah). Siku hampir menempel sisi badan.
            if (peakReached && isElbowDroppedTooLow(metrics)) {
                elbowDropViolationDetected = true
            }
        }

        if (cycleActive && !peakReached && isBackToBottom(metrics)) {
            val completedViolation = if (cycleMaxElbowAngle >= START_ELBOW_MIN) {
                ShoulderPressFormError.RANGE_INCOMPLETE
            } else {
                null
            }
            val resetFeedback = buildFeedback(completedViolation, "Siap untuk repetisi berikutnya")
            val repCompleted = completedViolation != null
            val repStatus = if (repCompleted) BicepRepStatus.REP_BAD else BicepRepStatus.IDLE
            reset(keepReady = true)
            return RuleResult(
                isValid = !repCompleted,
                feedback = resetFeedback,
                primaryMetric = metrics.avgElbowAngle,
                secondaryMetric = metrics.avgArmTorsoAngle,
                torsoAngle = metrics.torsoAngle,
                liveFeedback = resetFeedback,
                repStatus = repStatus,
                repCompleted = repCompleted,
                shouldCountRep = false
            )
        }

        if (cycleActive && peakReached && isBackToBottom(metrics)) {
            val immediateViolation = determineImmediateReturnViolation(now)
            if (immediateViolation == null && returnHoldStartTimeMs == 0L) {
                returnHoldStartTimeMs = now
                return RuleResult(
                    isValid = true,
                    feedback = "Tahan siku sejajar bahu sebentar",
                    primaryMetric = metrics.avgElbowAngle,
                    secondaryMetric = metrics.avgArmTorsoAngle,
                    torsoAngle = metrics.torsoAngle,
                    liveFeedback = "Tahan siku sejajar bahu sebentar",
                    repStatus = BicepRepStatus.IN_PROGRESS,
                    repCompleted = false,
                    shouldCountRep = false
                )
            }
            if (immediateViolation == null && now - returnHoldStartTimeMs < RETURN_HOLD_MIN_MS) {
                return RuleResult(
                    isValid = true,
                    feedback = "Tahan siku sejajar bahu sebentar",
                    primaryMetric = metrics.avgElbowAngle,
                    secondaryMetric = metrics.avgArmTorsoAngle,
                    torsoAngle = metrics.torsoAngle,
                    liveFeedback = "Tahan siku sejajar bahu sebentar",
                    repStatus = BicepRepStatus.IN_PROGRESS,
                    repCompleted = false,
                    shouldCountRep = false
                )
            }
            val completedViolation = finishCycle(now)
            lastCompletedWithElbowDrop = completedViolation == ShoulderPressFormError.ELBOW_DROP
            val finalFeedback = buildFeedback(
                error = completedViolation,
                defaultMessage = "Gerakan benar, dorongan lurus ke atas dan postur stabil"
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
        if (cycleActive && peakReached) {
            returnHoldStartTimeMs = 0L
        }

        val liveFeedback = when {
            tempoViolationDetected -> "Tempo terlalu cepat, perlambat gerakan"
            elbowLockoutViolationDetected -> "Jangan luruskan siku sepenuhnya"
            elbowDropViolationDetected -> "Jangan turunkan siku terlalu rendah, jaga setinggi bahu"
            returnHoldStartTimeMs > 0L -> "Tahan siku sejajar bahu sebentar"
            !torsoStableNow -> "Dorong beban sampai hampir lurus dan jaga punggung tetap stabil"
            !symmetricNow -> "Jaga dorongan kedua lengan tetap simetris"
            cycleActive && !peakReached -> "Dorong beban sampai hampir lurus dan jaga punggung tetap stabil"
            cycleActive -> "Dorong beban ke atas dengan kontrol"
            else -> "Siap untuk repetisi berikutnya"
        }

        return RuleResult(
            isValid = true,
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
        reset(keepReady = false)
    }

    private fun reset(keepReady: Boolean) {
        cycleActive = false
        cycleStartTimeMs = 0L
        cyclePeakTimeMs = 0L
        cycleLastSampleTimeMs = 0L
        readyFrames = if (keepReady) READY_FRAMES else 0
        peakReached = false
        tempoViolationDetected = false
        elbowLockoutViolationDetected = false
        elbowDropViolationDetected = false
        lastCompletedWithElbowDrop = false
        anchorTorsoAngle = 0f
        torsoViolationMs = 0L
        symmetryViolationMs = 0L
        cycleMaxElbowAngle = 0f
        returnHoldStartTimeMs = 0L
        // Jika rep baru saja selesai, beri grace 12 frame agar pesan
        // "Letakkan dumbel di atas bahu" tidak muncul saat tangan transisi turun
        postRepGraceFrames = if (keepReady) 12 else 0
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
        val hipDistance = abs(leftHip.x - rightHip.x)
        val midShoulderX = (leftShoulder.x + rightShoulder.x) / 2f
        val midShoulderY = (leftShoulder.y + rightShoulder.y) / 2f
        val midHipX = (leftHip.x + rightHip.x) / 2f
        val midHipY = (leftHip.y + rightHip.y) / 2f
        val torsoAngle = AngleUtils.verticalAngle(midHipX, midHipY, midShoulderX, midShoulderY)

        return RepMetrics(
            shoulderDistance = shoulderDistance,
            hipDistance = hipDistance,
            avgElbowAngle = (leftElbowAngle + rightElbowAngle) / 2f,
            avgArmTorsoAngle = (leftArmTorsoAngle + rightArmTorsoAngle) / 2f,
            leftElbowAngle = leftElbowAngle,
            rightElbowAngle = rightElbowAngle,
            leftWristBelowShoulder = leftWrist.y - leftShoulder.y,
            rightWristBelowShoulder = rightWrist.y - rightShoulder.y,
            elbowDiff = abs(leftElbowAngle - rightElbowAngle),
            armTorsoDiff = abs(leftArmTorsoAngle - rightArmTorsoAngle),
            wristHeightDiff = abs(leftWrist.y - rightWrist.y),
            torsoAngle = torsoAngle,
            // Positif = siku di bawah bahu (sistem koordinat Y bertambah ke bawah)
            avgElbowBelowShoulder = ((leftElbow.y - leftShoulder.y) + (rightElbow.y - rightShoulder.y)) / 2f,
            maxElbowBelowShoulder = maxOf(leftElbow.y - leftShoulder.y, rightElbow.y - rightShoulder.y),
            avgShoulderHipHeight = (
                abs(leftHip.y - leftShoulder.y) +
                    abs(rightHip.y - rightShoulder.y)
                ) / 2f
        )
    }

    private fun updateReadyState(metrics: RepMetrics) {
        if (cycleActive) return

        if (isReadyStartPosition(metrics)) {
            readyFrames = (readyFrames + 1).coerceAtMost(READY_FRAMES + 2)
        } else {
            readyFrames = 0
        }
    }

    private fun canStartCycle(metrics: RepMetrics): Boolean {
        return readyFrames >= READY_FRAMES && metrics.avgElbowAngle >= START_ELBOW_MIN
    }

    private fun isFacingFront(metrics: RepMetrics): Boolean {
        return metrics.shoulderDistance >= FRONT_VIEW_SHOULDER_DISTANCE_MIN &&
            (metrics.hipDistance >= FRONT_VIEW_HIP_DISTANCE_MIN || metrics.shoulderDistance >= FRONT_VIEW_SHOULDER_DISTANCE_MIN * 1.2f)
    }

    private fun isReadyStartPosition(metrics: RepMetrics): Boolean {
        return metrics.avgElbowAngle in READY_ELBOW_MIN..READY_ELBOW_MAX &&
            metrics.maxElbowBelowShoulder <= READY_ELBOW_BELOW_SHOULDER_MAX &&
            metrics.leftWristBelowShoulder <= READY_WRIST_BELOW_SHOULDER_MAX &&
            metrics.rightWristBelowShoulder <= READY_WRIST_BELOW_SHOULDER_MAX &&
            metrics.wristHeightDiff <= WRIST_HEIGHT_SYMMETRY_THRESHOLD
    }

    private fun startCycle(now: Long, metrics: RepMetrics) {
        cycleActive = true
        cycleStartTimeMs = now
        cyclePeakTimeMs = 0L
        cycleLastSampleTimeMs = now
        readyFrames = 0
        peakReached = false
        tempoViolationDetected = false
        elbowLockoutViolationDetected = false
        anchorTorsoAngle = metrics.torsoAngle
        torsoViolationMs = 0L
        symmetryViolationMs = 0L
        cycleMaxElbowAngle = metrics.avgElbowAngle
        returnHoldStartTimeMs = 0L
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
        reset(keepReady = true)
        return error
    }

    private fun determineImmediateReturnViolation(now: Long): ShoulderPressFormError? {
        val fullRepDuration = now - cycleStartTimeMs
        if (cyclePeakTimeMs > 0L && fullRepDuration < MIN_FULL_REP_MS) {
            return ShoulderPressFormError.TEMPO_TOO_FAST
        }
        return when {
            elbowLockoutViolationDetected -> ShoulderPressFormError.ELBOW_LOCKOUT
            elbowDropViolationDetected -> ShoulderPressFormError.ELBOW_DROP
            hasSignificantViolation(torsoViolationMs, fullRepDuration) -> ShoulderPressFormError.TORSO_UNSTABLE
            hasSignificantViolation(symmetryViolationMs, fullRepDuration) -> ShoulderPressFormError.ASYMMETRIC
            else -> null
        }
    }

    private fun determineCompletedViolation(fullRepDurationMs: Long): ShoulderPressFormError? {
        if (tempoViolationDetected) {
            return ShoulderPressFormError.TEMPO_TOO_FAST
        }
        if (!peakReached) {
            return ShoulderPressFormError.RANGE_INCOMPLETE
        }
        if (elbowLockoutViolationDetected) {
            return ShoulderPressFormError.ELBOW_LOCKOUT
        }
        if (elbowDropViolationDetected) {
            return ShoulderPressFormError.ELBOW_DROP
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

    private fun isElbowLockedOut(metrics: RepMetrics): Boolean {
        return metrics.leftElbowAngle >= ELBOW_LOCKOUT_MAX ||
            metrics.rightElbowAngle >= ELBOW_LOCKOUT_MAX
    }

    // Penilaian shoulder press dikunci saat beban turun lagi.
    // Siku boleh sedikit di bawah bahu, tetapi tidak boleh turun dalam ke arah pinggang.
    private fun isElbowDroppedTooLow(metrics: RepMetrics): Boolean {
        val toleratedDrop = maxOf(
            RETURN_ELBOW_BELOW_SHOULDER_MAX,
            metrics.avgShoulderHipHeight * ELBOW_DROP_TORSO_RATIO
        )
        return metrics.maxElbowBelowShoulder > toleratedDrop
    }

    private fun isOverheadLockout(metrics: RepMetrics): Boolean {
        return metrics.avgElbowAngle >= ELBOW_LOCKOUT_MAX &&
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
            ShoulderPressFormError.ELBOW_LOCKOUT -> "Jangan luruskan siku sepenuhnya"
            ShoulderPressFormError.ELBOW_DROP -> "Jangan turunkan siku terlalu rendah, jaga setinggi bahu"
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
        val hipDistance: Float,
        val avgElbowAngle: Float,
        val avgArmTorsoAngle: Float,
        val leftElbowAngle: Float,
        val rightElbowAngle: Float,
        val leftWristBelowShoulder: Float,
        val rightWristBelowShoulder: Float,
        val elbowDiff: Float,
        val armTorsoDiff: Float,
        val wristHeightDiff: Float,
        val torsoAngle: Float,
        // Rata-rata seberapa jauh siku di bawah bahu (positif = siku lebih rendah dari bahu)
        val avgElbowBelowShoulder: Float,
        val maxElbowBelowShoulder: Float,
        val avgShoulderHipHeight: Float
    )

    private enum class ShoulderPressFormError {
        RANGE_INCOMPLETE,
        TORSO_UNSTABLE,
        ELBOW_LOCKOUT,
        ELBOW_DROP,
        ASYMMETRIC,
        TEMPO_TOO_FAST
    }
}
