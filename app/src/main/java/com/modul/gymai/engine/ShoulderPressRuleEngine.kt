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
        // Setelah latch terkonfirmasi, hanya lepas latch jika bahu benar-benar sempit
        // (indikasi tubuh memang berpaling, bukan sekadar shoulder press di posisi atas)
        private const val FRONT_VIEW_STRICT_SHOULDER_MIN = 0.06f
        // Jumlah frame berturut-turut untuk mengkonfirmasi "menghadap depan"
        private const val FRONT_VIEW_CONFIRM_FRAMES = 3
        private const val READY_FRAMES = 4
        private const val READY_ELBOW_MIN = 78f
        private const val READY_ELBOW_MAX = 115f
        private const val READY_SHOULDER_TORSO_MIN = 68f
        private const val READY_SHOULDER_TORSO_MAX = 92f
        private const val READY_ELBOW_BELOW_SHOULDER_MIN = 0.015f
        private const val READY_ELBOW_BELOW_SHOULDER_MAX = 0.22f
        private const val READY_WRIST_BELOW_SHOULDER_MAX = 0.20f
        private const val READY_ELBOW_DROP_TORSO_RATIO = 0.58f
        private const val READY_ELBOW_TORSO_SYMMETRY_THRESHOLD = 18f
        private const val READY_WRIST_HEIGHT_SYMMETRY_THRESHOLD = 0.14f
        private const val START_ELBOW_MIN = 144f
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
        private const val RETURN_ELBOW_BELOW_SHOULDER_MAX = 0.16f
        private const val ELBOW_DROP_TORSO_RATIO = 0.46f
        private const val ELBOW_COLLAPSED_TORSO_MAX = 52f
        private const val ELBOW_COLLAPSED_DROP_TORSO_RATIO = 0.50f
        private const val HANDS_DOWN_WRIST_BELOW_SHOULDER_MIN = 0.16f
        private const val HANDS_DOWN_WRIST_DROP_TORSO_RATIO = 0.48f
        private const val HANDS_DOWN_SINGLE_WRIST_EXTRA = 0.06f
        private const val RETURN_SAFE_ELBOW_BELOW_SHOULDER_MAX = 0.15f
        private const val RETURN_SAFE_ELBOW_DROP_TORSO_RATIO = 0.44f
        private const val POST_REP_GRACE_FRAMES = 30
    }

    private var cycleActive = false
    private var cycleStartTimeMs = 0L
    private var cyclePeakTimeMs = 0L
    private var cycleLastSampleTimeMs = 0L
    private var readyFrames = 0
    private var readyLatched = false
    private var peakReached = false
    private var tempoViolationDetected = false
    // Tanda internal: tempo dilanggar tapi belum ditampilkan ke user
    // Akan ditampilkan sebagai live feedback saat fase TURUN dimulai (bukan di momen puncak)
    private var tempoViolatedInternal = false
    private var elbowLockoutViolationDetected = false
    private var elbowDropViolationDetected = false
    private var badRepAlreadyReported = false
    private var lastCompletedWithElbowDrop = false
    private var anchorTorsoAngle = 0f
    private var torsoViolationMs = 0L
    private var symmetryViolationMs = 0L
    private var cycleMaxElbowAngle = 0f
    private var returnHoldStartTimeMs = 0L
    // Grace period setelah rep selesai: jangan tampilkan pesan posisi awal
    // saat tangan sedang dalam transisi turun kembali ke starting position
    private var postRepGraceFrames = 0
    // Latch "menghadap depan" — sekali terkonfirmasi N frame berturut-turut,
    // pertahankan meskipun shoulder press di posisi atas membuat shoulderDistance turun
    private var facingFrontLatched = false
    private var facingFrontConfirmFrames = 0

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
                feedback = "Pastikan seluruh tubuh terlihat di kamera",
                liveFeedback = "Pastikan seluruh tubuh terlihat di kamera",
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

        if (isReadyStartPosition(metrics) || isHandsFullyDown(metrics)) {
            badRepAlreadyReported = false
        }

        if (!isReadyStartPosition(metrics) && isHandsFullyDown(metrics)) {
            reset(keepReady = false)
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

        if (!cycleActive && lastCompletedWithElbowDrop) {
            if (!isReadyStartPosition(metrics)) {
                if (isHandsFullyDown(metrics)) {
                    lastCompletedWithElbowDrop = false
                } else {
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
            }
            lastCompletedWithElbowDrop = false
        }

        if (!cycleActive && postRepGraceFrames == 0 && !isReadyStartPosition(metrics)) {
            if (readyLatched && isLeavingReadyToPress(metrics)) {
                startCycle(now, metrics)
            } else if (readyLatched && !isHandsFullyDown(metrics)) {
                val elbowCollapsed = isElbowCollapsedToSide(metrics)
                val shouldCompleteBadRep = elbowCollapsed && !badRepAlreadyReported
                if (shouldCompleteBadRep) {
                    badRepAlreadyReported = true
                    lastCompletedWithElbowDrop = true
                }
                val feedback = buildFeedback(
                    error = if (elbowCollapsed) ShoulderPressFormError.ELBOW_DROP else ShoulderPressFormError.RANGE_INCOMPLETE,
                    defaultMessage = "Gerakan shoulder press kurang tepat"
                )
                return RuleResult(
                    isValid = false,
                    feedback = feedback,
                    primaryMetric = metrics.avgElbowAngle,
                    secondaryMetric = metrics.avgArmTorsoAngle,
                    torsoAngle = metrics.torsoAngle,
                    liveFeedback = feedback,
                    repStatus = BicepRepStatus.REP_BAD,
                    repCompleted = shouldCompleteBadRep,
                    shouldCountRep = false,
                    isPositionIssue = false
                )
            } else {
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
        }

        val topRangeReachedNow = isTopRange(metrics)
        val overheadReachedNow = topRangeReachedNow || isOverheadLockout(metrics)

        if (cycleActive) {
            cycleLastSampleTimeMs = now
            cycleMaxElbowAngle = maxOf(cycleMaxElbowAngle, metrics.avgElbowAngle)

            if (now > cycleStartTimeMs && overheadReachedNow) {
                peakReached = true
            }
            // ELBOW_LOCKOUT tidak dievaluasi — meluruskan siku di puncak shoulder press adalah hal wajar
            if (cyclePeakTimeMs == 0L && now > cycleStartTimeMs && overheadReachedNow) {
                cyclePeakTimeMs = now
                // Simpan dulu di flag internal — JANGAN langsung set tempoViolationDetected
                // Feedback "Tempo terlalu cepat" akan ditampilkan saat fase TURUN dimulai
                if (cyclePeakTimeMs - cycleStartTimeMs < MIN_UP_PHASE_MS) {
                    tempoViolatedInternal = true
                }
            }

            // Propagasi ke live feedback hanya saat TURUN (siku sudah mulai turun dari puncak)
            // Threshold 8° untuk memastikan benar-benar sudah di fase turun, bukan noise di puncak
            val descentStarted = peakReached && cycleMaxElbowAngle > 0f &&
                metrics.avgElbowAngle < cycleMaxElbowAngle - 8f
            if (descentStarted && !tempoViolationDetected) {
                if (tempoViolatedInternal) {
                    // Ascent terlalu cepat — tampilkan saat turun
                    tempoViolationDetected = true
                } else if (cyclePeakTimeMs > 0L && (now - cycleStartTimeMs) < MIN_FULL_REP_MS) {
                    // Total rep heading too fast — deteksi live saat turun
                    tempoViolationDetected = true
                    tempoViolatedInternal = true
                }
            }

            // Deteksi siku jatuh terlalu rendah — hanya dicheck setelah peak tercapai
            // (fase turun kembali ke bawah). Siku hampir menempel sisi badan.
            if (isElbowCollapsedToSide(metrics) || (peakReached && isElbowDroppedTooLow(metrics))) {
                elbowDropViolationDetected = true
            }
        }

        if (cycleActive && !peakReached && isBackToBottom(metrics)) {
            // Cek tempo terlebih dahulu — gerakan terlalu cepat bisa menyebabkan
            // peak terlewat dalam satu frame sebelum kembali ke bawah
            val fullRepDurationSoFar = now - cycleStartTimeMs
            if (fullRepDurationSoFar < MIN_FULL_REP_MS &&
                (cyclePeakTimeMs > 0L || cycleMaxElbowAngle >= ELBOW_PEAK_MIN)) {
                // Tempo terlalu cepat: peak terekam langsung, ATAU siku sudah naik
                // tinggi (>= ELBOW_PEAK_MIN) tapi frame puncak terlewat karena gerakan kilat
                tempoViolationDetected = true
            }
            val completedViolation = when {
                tempoViolationDetected -> ShoulderPressFormError.TEMPO_TOO_FAST
                elbowDropViolationDetected -> ShoulderPressFormError.ELBOW_DROP
                cycleMaxElbowAngle >= START_ELBOW_MIN -> ShoulderPressFormError.RANGE_INCOMPLETE
                else -> null
            }
            val resetFeedback = buildFeedback(completedViolation, "Siap untuk repetisi berikutnya")
            val repCompleted = completedViolation != null && !badRepAlreadyReported
            if (completedViolation != null) {
                badRepAlreadyReported = true
            }
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
            val immediateViolation = determineImmediateReturnViolation(now, metrics)
            if (immediateViolation != null) {
                val shouldCompleteBadRep = !badRepAlreadyReported
                badRepAlreadyReported = true
                reset(keepReady = true)
                val feedback = buildFeedback(immediateViolation, "Gerakan shoulder press kurang tepat")
                return RuleResult(
                    isValid = false,
                    feedback = feedback,
                    primaryMetric = metrics.avgElbowAngle,
                    secondaryMetric = metrics.avgArmTorsoAngle,
                    torsoAngle = metrics.torsoAngle,
                    liveFeedback = feedback,
                    repStatus = BicepRepStatus.REP_BAD,
                    repCompleted = shouldCompleteBadRep,
                    shouldCountRep = false
                )
            }
            if (returnHoldStartTimeMs == 0L) {
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
            if (now - returnHoldStartTimeMs < RETURN_HOLD_MIN_MS) {
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
                defaultMessage = "Gerakan benar, dorongan lurus ke atas, siku sejajar dengan bahu"
            )
            val repStatus = if (completedViolation == null) BicepRepStatus.REP_GOOD else BicepRepStatus.REP_BAD
            val shouldCountRep = completedViolation == null
            val repCompleted = completedViolation == null || !badRepAlreadyReported
            if (completedViolation != null) {
                badRepAlreadyReported = true
            }
            return RuleResult(
                isValid = shouldCountRep,
                feedback = finalFeedback,
                primaryMetric = metrics.avgElbowAngle,
                secondaryMetric = metrics.avgArmTorsoAngle,
                torsoAngle = metrics.torsoAngle,
                liveFeedback = finalFeedback,
                repStatus = repStatus,
                repCompleted = repCompleted,
                shouldCountRep = shouldCountRep
            )
        }
        if (cycleActive && peakReached) {
            returnHoldStartTimeMs = 0L
        }

        val liveFeedback = when {
            tempoViolationDetected -> "Tempo terlalu cepat, perlambat gerakan"
            elbowDropViolationDetected -> "Jangan turunkan siku terlalu rendah, jaga setinggi bahu"
            returnHoldStartTimeMs > 0L -> "Tahan siku sejajar bahu sebentar"
            cycleActive -> "Dorong beban ke atas dengan kontrol"
            else -> "Dumbel di atas bahu, siap dorong ke atas"
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
        readyLatched = keepReady
        peakReached = false
        tempoViolationDetected = false
        tempoViolatedInternal = false
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
        postRepGraceFrames = if (keepReady) POST_REP_GRACE_FRAMES else 0
        // Latch facing-front TIDAK direset saat antar repetisi — orang tetap menghadap depan
        // Hanya direset jika keepReady=false (sesi baru / cancel)
        if (!keepReady) {
            facingFrontLatched = false
            facingFrontConfirmFrames = 0
        }
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
        val leftElbowTorsoAngle = AngleUtils.angleBetween(
            leftHip.x, leftHip.y,
            leftShoulder.x, leftShoulder.y,
            leftElbow.x, leftElbow.y
        )
        val rightElbowTorsoAngle = AngleUtils.angleBetween(
            rightHip.x, rightHip.y,
            rightShoulder.x, rightShoulder.y,
            rightElbow.x, rightElbow.y
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
            avgElbowTorsoAngle = (leftElbowTorsoAngle + rightElbowTorsoAngle) / 2f,
            leftElbowAngle = leftElbowAngle,
            rightElbowAngle = rightElbowAngle,
            leftWristBelowShoulder = leftWrist.y - leftShoulder.y,
            rightWristBelowShoulder = rightWrist.y - rightShoulder.y,
            avgWristBelowShoulder = ((leftWrist.y - leftShoulder.y) + (rightWrist.y - rightShoulder.y)) / 2f,
            elbowDiff = abs(leftElbowAngle - rightElbowAngle),
            armTorsoDiff = abs(leftArmTorsoAngle - rightArmTorsoAngle),
            elbowTorsoDiff = abs(leftElbowTorsoAngle - rightElbowTorsoAngle),
            wristHeightDiff = abs(leftWrist.y - rightWrist.y),
            torsoAngle = torsoAngle,
            // Positif = siku di bawah bahu (sistem koordinat Y bertambah ke bawah)
            avgElbowBelowShoulder = ((leftElbow.y - leftShoulder.y) + (rightElbow.y - rightShoulder.y)) / 2f,
            minElbowBelowShoulder = minOf(leftElbow.y - leftShoulder.y, rightElbow.y - rightShoulder.y),
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
            readyLatched = true
        } else {
            if (!readyLatched) {
                readyFrames = 0
            }
        }
    }

    private fun canStartCycle(metrics: RepMetrics): Boolean {
        return readyLatched && isLeavingReadyToPress(metrics)
    }

    private fun isLeavingReadyToPress(metrics: RepMetrics): Boolean {
        return metrics.avgElbowAngle > READY_ELBOW_MAX + 6f ||
            metrics.avgElbowTorsoAngle > READY_SHOULDER_TORSO_MAX + 8f ||
            metrics.avgArmTorsoAngle >= 105f
    }

    private fun isFacingFront(metrics: RepMetrics): Boolean {
        val clearlyFront = metrics.shoulderDistance >= FRONT_VIEW_SHOULDER_DISTANCE_MIN &&
            (metrics.hipDistance >= FRONT_VIEW_HIP_DISTANCE_MIN ||
                metrics.shoulderDistance >= FRONT_VIEW_SHOULDER_DISTANCE_MIN * 1.2f)

        if (clearlyFront) {
            // Akumulasi frame konfirmasi — butuh FRONT_VIEW_CONFIRM_FRAMES berturut-turut
            facingFrontConfirmFrames = (facingFrontConfirmFrames + 1).coerceAtMost(FRONT_VIEW_CONFIRM_FRAMES + 2)
            if (facingFrontConfirmFrames >= FRONT_VIEW_CONFIRM_FRAMES) {
                facingFrontLatched = true  // Terkunci: orang menghadap depan
            }
        } else {
            facingFrontConfirmFrames = 0  // Reset counter jika tidak terdeteksi
        }

        if (facingFrontLatched) {
            // Pertahankan latch KECUALI bahu benar-benar sangat sempit
            // (bukti kuat orang sudah berpaling — bukan sekadar shoulder press di atas)
            val shoulderVeryNarrow = metrics.shoulderDistance < FRONT_VIEW_STRICT_SHOULDER_MIN
            if (shoulderVeryNarrow) {
                facingFrontLatched = false  // Lepas latch
                facingFrontConfirmFrames = 0
                return false
            }
            return true  // Masih dianggap menghadap depan
        }

        return clearlyFront
    }

    private fun isReadyStartPosition(metrics: RepMetrics): Boolean {
        val elbowDropLimit = maxOf(
            READY_ELBOW_BELOW_SHOULDER_MAX,
            metrics.avgShoulderHipHeight * READY_ELBOW_DROP_TORSO_RATIO
        )
        return metrics.avgElbowAngle in READY_ELBOW_MIN..READY_ELBOW_MAX &&
            metrics.avgElbowTorsoAngle in READY_SHOULDER_TORSO_MIN..READY_SHOULDER_TORSO_MAX &&
            metrics.avgElbowBelowShoulder >= READY_ELBOW_BELOW_SHOULDER_MIN &&
            metrics.minElbowBelowShoulder >= -READY_ELBOW_BELOW_SHOULDER_MIN &&
            metrics.maxElbowBelowShoulder <= elbowDropLimit &&
            metrics.leftWristBelowShoulder <= READY_WRIST_BELOW_SHOULDER_MAX &&
            metrics.rightWristBelowShoulder <= READY_WRIST_BELOW_SHOULDER_MAX &&
            metrics.elbowTorsoDiff <= READY_ELBOW_TORSO_SYMMETRY_THRESHOLD &&
            metrics.wristHeightDiff <= READY_WRIST_HEIGHT_SYMMETRY_THRESHOLD
    }

    private fun startCycle(now: Long, metrics: RepMetrics) {
        cycleActive = true
        cycleStartTimeMs = now
        cyclePeakTimeMs = 0L
        cycleLastSampleTimeMs = now
        readyFrames = 0
        readyLatched = true
        peakReached = false
        tempoViolationDetected = false
        tempoViolatedInternal = false
        elbowLockoutViolationDetected = false
        elbowDropViolationDetected = false
        // Reset per siklus: setiap rep baru berhak mendapat feedback TTS
        // Tanpa ini, rep cepat berulang menyebabkan badRepAlreadyReported tetap true
        // sehingga repCompleted = false dan TTS tidak pernah bicara setelah rep pertama
        badRepAlreadyReported = false
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

    private fun determineImmediateReturnViolation(now: Long, metrics: RepMetrics): ShoulderPressFormError? {
        val fullRepDuration = now - cycleStartTimeMs
        // Set tempoViolationDetected di sini juga agar determineCompletedViolation konsisten
        if (cyclePeakTimeMs > 0L && fullRepDuration < MIN_FULL_REP_MS) {
            tempoViolationDetected = true
        }
        return when {
            tempoViolationDetected -> ShoulderPressFormError.TEMPO_TOO_FAST
            elbowDropViolationDetected -> ShoulderPressFormError.ELBOW_DROP
            !isSafeReturnPosition(metrics) -> ShoulderPressFormError.ELBOW_DROP
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
        if (elbowDropViolationDetected) {
            return ShoulderPressFormError.ELBOW_DROP
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

    private fun isElbowCollapsedToSide(metrics: RepMetrics): Boolean {
        val deepDrop = maxOf(
            READY_ELBOW_BELOW_SHOULDER_MAX,
            metrics.avgShoulderHipHeight * ELBOW_COLLAPSED_DROP_TORSO_RATIO
        )
        return metrics.avgElbowTorsoAngle <= ELBOW_COLLAPSED_TORSO_MAX ||
            (
                metrics.avgElbowBelowShoulder > deepDrop &&
                    metrics.avgElbowTorsoAngle < READY_SHOULDER_TORSO_MIN
                )
    }

    private fun isHandsFullyDown(metrics: RepMetrics): Boolean {
        val wristDropLimit = maxOf(
            HANDS_DOWN_WRIST_BELOW_SHOULDER_MIN,
            metrics.avgShoulderHipHeight * HANDS_DOWN_WRIST_DROP_TORSO_RATIO
        )
        val bothWristsClearlyDown =
            metrics.leftWristBelowShoulder >= wristDropLimit &&
                metrics.rightWristBelowShoulder >= wristDropLimit
        val averageWristClearlyDown = metrics.avgWristBelowShoulder >= wristDropLimit
        val oneWristFarDown =
            maxOf(metrics.leftWristBelowShoulder, metrics.rightWristBelowShoulder) >=
                wristDropLimit + HANDS_DOWN_SINGLE_WRIST_EXTRA

        return bothWristsClearlyDown || (averageWristClearlyDown && oneWristFarDown)
    }

    private fun isOverheadLockout(metrics: RepMetrics): Boolean {
        return metrics.avgElbowAngle >= ELBOW_LOCKOUT_MAX &&
            metrics.avgArmTorsoAngle in ARM_TORSO_PEAK_MIN..ARM_TORSO_PEAK_MAX
    }

    private fun isBackToBottom(metrics: RepMetrics): Boolean {
        return metrics.avgElbowAngle <= RETURN_ELBOW_MAX
    }

    private fun isSafeReturnPosition(metrics: RepMetrics): Boolean {
        val safeDrop = maxOf(
            RETURN_SAFE_ELBOW_BELOW_SHOULDER_MAX,
            metrics.avgShoulderHipHeight * RETURN_SAFE_ELBOW_DROP_TORSO_RATIO
        )
        return metrics.maxElbowBelowShoulder <= safeDrop
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
            ShoulderPressFormError.RANGE_INCOMPLETE -> "Dorong beban sampai hampir lurus ke atas"
            ShoulderPressFormError.ELBOW_DROP -> "Jangan turunkan siku terlalu rendah, jaga setinggi bahu"
            ShoulderPressFormError.TEMPO_TOO_FAST -> "Tempo terlalu cepat, perlambat gerakan"
            // Tipe berikut tidak aktif tapi dipertahankan di enum untuk kompatibilitas
            ShoulderPressFormError.TORSO_UNSTABLE -> "Jaga punggung tetap stabil"
            ShoulderPressFormError.ELBOW_LOCKOUT -> "Jangan luruskan siku sepenuhnya"
            ShoulderPressFormError.ASYMMETRIC -> "Jaga dorongan kedua lengan tetap simetris"
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
        val avgElbowTorsoAngle: Float,
        val leftElbowAngle: Float,
        val rightElbowAngle: Float,
        val leftWristBelowShoulder: Float,
        val rightWristBelowShoulder: Float,
        val avgWristBelowShoulder: Float,
        val elbowDiff: Float,
        val armTorsoDiff: Float,
        val elbowTorsoDiff: Float,
        val wristHeightDiff: Float,
        val torsoAngle: Float,
        // Rata-rata seberapa jauh siku di bawah bahu (positif = siku lebih rendah dari bahu)
        val avgElbowBelowShoulder: Float,
        val minElbowBelowShoulder: Float,
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
