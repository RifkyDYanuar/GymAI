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
        private const val MIN_CONF = 0.35f          // Diturunkan agar toleran di posisi squat bawah
        private const val MIN_CONF_STRICT = 0.45f   // Digunakan hanya untuk keypoint tubuh atas
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
        // Lebar bahu minimal (rasio frame) yang menandakan orang menghadap ke depan kamera
        // Saat menghadap samping, lebar bahu akan lebih kecil dari threshold ini
        private const val FRONT_FACING_SHOULDER_MIN = 0.15f
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
    private var lastValidMetrics: RepMetrics? = null  // Cache metrics terakhir yang valid

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

        val rawMetrics = extractMetrics(pose.rawKeypoints)
        val metrics: RepMetrics

        if (rawMetrics == null) {
            // Saat cycle squat aktif ATAU pernah ada metrics valid sebelumnya,
            // gunakan cache untuk menghindari pesan orientasi yang salah di posisi squat dalam.
            val cached = lastValidMetrics
            if (cached != null) {
                // Ada data valid sebelumnya → gunakan sebagai fallback
                metrics = cached
            } else {
                // Benar-benar belum ada data valid sama sekali
                cancelCycle()
                val feedback = when {
                    hasUpperBodyWithoutLegs(pose.rawKeypoints) ->
                        // Terlihat tubuh atas tapi kaki tidak → jauh dari kamera
                        "Pastikan seluruh tubuh terlihat di kamera"
                    isClearlyFacingFront(pose.rawKeypoints) ->
                        // Terbukti positif menghadap depan (bahu lebar)
                        "Harus menghadap ke samping serong"
                    else ->
                        // Tidak bisa membuktikan orientasi → pesan generik
                        // (bisa jadi squat dalam dengan confidence rendah, bukan salah orientasi)
                        "Pastikan seluruh tubuh terlihat di kamera"
                }
                return RuleResult(
                    isValid = false,
                    feedback = feedback,
                    liveFeedback = feedback,
                    repStatus = currentRepStatus(),
                    isPositionIssue = true
                )
            }
        } else {
            metrics = rawMetrics
            lastValidMetrics = rawMetrics   // Simpan metrics valid terakhir
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
        lastValidMetrics = null             // Reset cache metrics
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

        // Shoulder menggunakan threshold lebih ketat (posisi bahu selalu terlihat jelas)
        // Hip, knee, ankle menggunakan threshold lebih rendah karena sendi tertekuk saat squat dalam
        if (
            leftShoulder.confidence <= MIN_CONF_STRICT ||
            rightShoulder.confidence <= MIN_CONF_STRICT
        ) {
            return null
        }
        if (
            leftHip.confidence <= MIN_CONF ||
            rightHip.confidence <= MIN_CONF ||
            leftKnee.confidence <= MIN_CONF ||
            rightKnee.confidence <= MIN_CONF ||
            leftAnkle.confidence <= MIN_CONF ||
            rightAnkle.confidence <= MIN_CONF
        ) {
            return null
        }

        // Segment length check: dikurangi threshold untuk memungkinkan posisi squat dalam
        // dimana kaki tampak lebih pendek dari sudut kamera samping
        val leftUpperLeg = AngleUtils.distance(leftHip.x, leftHip.y, leftKnee.x, leftKnee.y)
        val rightUpperLeg = AngleUtils.distance(rightHip.x, rightHip.y, rightKnee.x, rightKnee.y)
        val leftLowerLeg = AngleUtils.distance(leftKnee.x, leftKnee.y, leftAnkle.x, leftAnkle.y)
        val rightLowerLeg = AngleUtils.distance(rightKnee.x, rightKnee.y, rightAnkle.x, rightAnkle.y)
        if (
            leftUpperLeg < 0.02f ||
            rightUpperLeg < 0.02f ||
            leftLowerLeg < 0.02f ||
            rightLowerLeg < 0.02f
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

    private fun hasUpperBodyWithoutLegs(keypoints: List<Keypoint>): Boolean {
        val upperIndexes = listOf(
            Keypoint.NOSE,
            Keypoint.LEFT_SHOULDER,
            Keypoint.RIGHT_SHOULDER,
            Keypoint.LEFT_ELBOW,
            Keypoint.RIGHT_ELBOW,
            Keypoint.LEFT_WRIST,
            Keypoint.RIGHT_WRIST
        )
        val visibleUpperBodyPoints = upperIndexes.count { index ->
            (keypoints.getOrNull(index)?.confidence ?: 0f) > MIN_CONF
        }
        return visibleUpperBodyPoints >= 3 && !hasVisibleLegs(keypoints)
    }

    /**
     * Mendeteksi secara positif apakah orang menghadap depan kamera.
     * Mengembalikan TRUE hanya jika:
     *   1. Kedua bahu terdeteksi dengan confidence tinggi
     *   2. Lebar bahu (horizontal) cukup besar — indikasi pandang depan
     *
     * Saat menghadap samping (serong): bahu tampak sempit → false → tidak tampilkan error orientasi.
     * Ini mencegah false positive "Harus menghadap ke samping serong" saat squat dalam dari sudut samping.
     */
    private fun isClearlyFacingFront(keypoints: List<Keypoint>): Boolean {
        val leftShoulder = keypoints.getOrNull(Keypoint.LEFT_SHOULDER) ?: return false
        val rightShoulder = keypoints.getOrNull(Keypoint.RIGHT_SHOULDER) ?: return false
        // Kedua bahu harus terdeteksi dengan confidence cukup baik
        if (leftShoulder.confidence < MIN_CONF_STRICT || rightShoulder.confidence < MIN_CONF_STRICT) {
            return false
        }
        // Lebar bahu horizontal — menghadap depan akan lebih lebar dari threshold
        val shoulderWidth = abs(leftShoulder.x - rightShoulder.x)
        return shoulderWidth > FRONT_FACING_SHOULDER_MIN
    }

    private fun hasVisibleLegs(keypoints: List<Keypoint>): Boolean {
        val leftLegVisible =
            (keypoints.getOrNull(Keypoint.LEFT_HIP)?.confidence ?: 0f) > MIN_CONF &&
                (keypoints.getOrNull(Keypoint.LEFT_KNEE)?.confidence ?: 0f) > MIN_CONF &&
                (keypoints.getOrNull(Keypoint.LEFT_ANKLE)?.confidence ?: 0f) > MIN_CONF

        val rightLegVisible =
            (keypoints.getOrNull(Keypoint.RIGHT_HIP)?.confidence ?: 0f) > MIN_CONF &&
                (keypoints.getOrNull(Keypoint.RIGHT_KNEE)?.confidence ?: 0f) > MIN_CONF &&
                (keypoints.getOrNull(Keypoint.RIGHT_ANKLE)?.confidence ?: 0f) > MIN_CONF

        return leftLegVisible || rightLegVisible
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
