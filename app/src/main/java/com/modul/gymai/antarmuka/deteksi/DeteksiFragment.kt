package com.modul.gymai.antarmuka.deteksi

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.CountDownTimer
import android.os.SystemClock
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageProxy
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.modul.gymai.camera.CameraManager
import com.modul.gymai.data.GymDatabase
import com.modul.gymai.data.WorkoutRepository
import com.modul.gymai.data.WorkoutSession
import com.modul.gymai.databinding.DialogEvaluationSavedBinding
import com.modul.gymai.databinding.FragmentDeteksiBinding
import com.modul.gymai.ml.BicepCurlSequenceClassifier
import com.modul.gymai.ml.BicepRepSequenceBuffer
import com.modul.gymai.pose.PoseDetectorHelper
import com.modul.gymai.pose.PoseResult
import com.modul.gymai.engine.*
import com.modul.gymai.pose.Keypoint
import com.modul.gymai.ui.MaterialSymbols
import com.modul.gymai.utils.EvaluationVideoRecorder
import com.modul.gymai.utils.RealtimeTtsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

class DeteksiFragment : Fragment() {

    companion object {
        private const val TAG = "DeteksiFragment"
        private const val ARG_EXERCISE_TYPE = "exerciseType"
        private const val MOVEMENT_THRESHOLD = 0.005f // Sensitivity for "Diam"
        private const val STILLNESS_LIMIT = 30 // Frames before showing "Diam"
        private const val REP_RESULT_DISPLAY_MS = 700L
        private const val RULES_COUNTDOWN_SECONDS = 3
        private const val LIVE_UI_UPDATE_INTERVAL_MS = 40L
        private const val MIN_LIVE_INFERENCE_INTERVAL_MS = 33L
        private const val DEFAULT_LIVE_INFERENCE_INTERVAL_MS = 42L
        private const val MAX_LIVE_INFERENCE_INTERVAL_MS = 50L
        private const val EVALUATION_RECORDING_FRAME_INTERVAL_MS = 140L
    }

    private var _binding: FragmentDeteksiBinding? = null
    private val binding get() = _binding!!

    private lateinit var exerciseType: ExerciseType
    private lateinit var poseDetectorHelper: PoseDetectorHelper
    private lateinit var repCounter: RepetitionCounter
    private lateinit var cameraManager: CameraManager
    private lateinit var engine: ExerciseRuleEngine

    private var isDetecting = false
    private var sessionStartTime = 0L
    private var confidenceSum = 0f
    private var confidenceCount = 0
    private var lastProcessedFpsTime = SystemClock.elapsedRealtime()
    private var processedFrameCount = 0
    private var detectionState = DetectionState.RULES_OVERLAY
    private var missingFrameCount = 0

    private val feedbackMap = mutableMapOf<String, Int>()
    private var lastUiUpdateTime = 0L
    private val scope = CoroutineScope(Dispatchers.IO + Job())

    private var lastKeypoints: List<Keypoint>? = null
    private var stillnessCount = 0
    private var repResultDisplayUntil = 0L
    private var repResultLabel = "SIAP"
    private var repResultFeedback = "Siap untuk repetisi berikutnya"
    private var repResultCorrect = true
    private var lastEvaluationDisplayPose: PoseResult? = null
    private var evaluationVideoPath: String? = null
    private var realtimeTtsManager: RealtimeTtsManager? = null
    private var hasRequestedEvaluationRecordingStart = false
    private var hasEvaluationRecordingFailed = false
    private var evaluationVideoRecorder: EvaluationVideoRecorder? = null
    private var hasSpokenRulesGuidance = false
    private var isSavingSession = false
    private var rulesCountdownTimer: CountDownTimer? = null
    private var isRulesCountdownRunning = false
    private var isRulesCountdownIntroPending = false
    private var lastInferenceRequestTime = 0L
    private var lastInferenceStartTime = 0L
    private var smoothedInferenceDurationMs = DEFAULT_LIVE_INFERENCE_INTERVAL_MS.toFloat()
    private var currentInferenceIntervalMs = DEFAULT_LIVE_INFERENCE_INTERVAL_MS
    private var lastAnimatedRulesCountdownValue = -1
    private var lastSpokenRulesCountdownValue = -1
    private var bicepSequenceClassifier: BicepCurlSequenceClassifier? = null
    private var bicepRepSequenceBuffer: BicepRepSequenceBuffer? = null
    private val evaluationFrameCaptureRunnable = object : Runnable {
        override fun run() {
            captureEvaluationFrame()
            val currentBinding = _binding ?: return
            if (
                isDetecting &&
                detectionState == DetectionState.EVALUATING &&
                evaluationVideoRecorder?.isActive() == true
            ) {
                currentBinding.previewView.postDelayed(this, EVALUATION_RECORDING_FRAME_INTERVAL_MS)
            }
        }
    }

    enum class DetectionState {
        RULES_OVERLAY,
        RULES_COUNTDOWN,
        EVALUATING
    }

    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startDetection()
        else {
            Toast.makeText(requireContext(), "Izin kamera diperlukan", Toast.LENGTH_LONG).show()
            findNavController().navigateUp()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentDeteksiBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val typeArg = arguments?.getString(ARG_EXERCISE_TYPE) ?: ExerciseType.SQUAT.name
        exerciseType = ExerciseType.fromString(typeArg)

        MaterialSymbols.applyToTree(binding.root)
        initComponents()
        setupHeader()
        binding.previewView.implementationMode = PreviewView.ImplementationMode.PERFORMANCE
        realtimeTtsManager = RealtimeTtsManager(requireContext()).also { ttsManager ->
            ttsManager.setOnUtteranceDoneListener { utteranceKey ->
                activity?.runOnUiThread {
                    when (utteranceKey) {
                        "rules:guidance" -> announceRulesCountdownIntroIfNeeded()
                        "rules:countdown-intro" -> beginRulesCountdownIfNeeded()
                    }
                }
            }
        }

        binding.btnStop.setOnClickListener { stopAndSaveSession() }
        binding.btnSwitchCamera.setOnClickListener {
            if (::cameraManager.isInitialized) {
                cameraManager.toggleCamera()
                binding.overlayView.clear()
                binding.overlayView.setFrontCamera(cameraManager.isFrontCamera())
            }
        }
        checkCameraPermission()
    }

    private fun setupHeader() {
        binding.tvExerciseName.text = "Deteksi ${exerciseType.displayName}"
    }

    private fun checkCameraPermission() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startDetection()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun initComponents() {
        // ML Kit Pose Detector
        poseDetectorHelper = PoseDetectorHelper(
            onResults = { poseResult -> processResults(poseResult) },
            onError = { error -> 
                lastInferenceStartTime = 0L
                requireActivity().runOnUiThread {
                    Toast.makeText(requireContext(), error, Toast.LENGTH_SHORT).show()
                }
            }
        )

        repCounter = RepetitionCounter(exerciseType)

        // Unified rule engine interface
        engine = when (exerciseType) {
            ExerciseType.SQUAT -> SquatRuleEngine()
            ExerciseType.BICEP_CURL -> BicepCurlRuleEngine()
            ExerciseType.LATERAL_RAISE -> LateralRaiseRuleEngine()
            ExerciseType.SHOULDER_PRESS -> ShoulderPressRuleEngine()
        }

        if (exerciseType == ExerciseType.BICEP_CURL) {
            bicepRepSequenceBuffer = BicepRepSequenceBuffer()
            if (bicepSequenceClassifier == null) {
                runCatching {
                    bicepSequenceClassifier = BicepCurlSequenceClassifier(requireContext())
                }.onFailure { error ->
                    Log.e(TAG, "Bicep CNN classifier failed to load", error)
                    bicepSequenceClassifier = null
                }
            }
        } else {
            bicepRepSequenceBuffer = null
        }
    }

    @OptIn(ExperimentalGetImage::class)
    private fun startDetection() {
        isDetecting = true
        sessionStartTime = SystemClock.elapsedRealtime()
        repCounter.reset()
        engine.reset()
        feedbackMap.clear()
        lastEvaluationDisplayPose = null
        evaluationVideoPath = null
        hasRequestedEvaluationRecordingStart = false
        hasEvaluationRecordingFailed = false
        stopEvaluationFrameCapture()
        evaluationVideoRecorder = null
        hasSpokenRulesGuidance = false
        isSavingSession = false
        bicepRepSequenceBuffer?.reset()
        resetRepResultState()
        isRulesCountdownRunning = false
        isRulesCountdownIntroPending = false
        lastAnimatedRulesCountdownValue = -1
        lastSpokenRulesCountdownValue = -1
        cancelRulesCountdown()
        realtimeTtsManager?.stop()

        cameraManager = CameraManager(
            context = requireContext(),
            lifecycleOwner = viewLifecycleOwner,
            previewView = binding.previewView,
            onFrameReady = { imageProxy -> 
                if (isDetecting) {
                    processFrame(imageProxy)
                } else {
                    imageProxy.close()
                }
            }
        )
        cameraManager.setPerformanceMode(CameraManager.PerformanceMode.LIVE_PERFORMANCE)
        cameraManager.startCamera()
        binding.overlayView.setFrontCamera(cameraManager.isFrontCamera())
        binding.layoutInitializing.visibility = View.GONE
        resetLivePerformanceMetrics()
        binding.tvFps.text = "-- FPS"

        detectionState = DetectionState.RULES_OVERLAY
        binding.layoutRulesOverlay.visibility = View.VISIBLE
        binding.tvRulesCountdown.visibility = View.GONE
        binding.tvRulesReadyHint.text = "Panduan evaluasi sedang dibacakan, tunggu sebentar"
        binding.tvLabel.text = "SIAP"
        binding.tvFeedback.text = "Dengarkan panduan evaluasi sebelum mulai"
        speakRulesGuidanceIfNeeded()
    }

    @OptIn(ExperimentalGetImage::class)
    private fun processFrame(imageProxy: ImageProxy) {
        when (detectionState) {
            DetectionState.RULES_OVERLAY,
            DetectionState.RULES_COUNTDOWN -> {
                imageProxy.close()
            }
            DetectionState.EVALUATING -> {
                val now = SystemClock.elapsedRealtime()
                if (poseDetectorHelper.isBusy()) {
                    imageProxy.close()
                    return
                }
                if (now - lastInferenceRequestTime < currentInferenceIntervalMs) {
                    imageProxy.close()
                    return
                }
                lastInferenceRequestTime = now
                lastInferenceStartTime = now
                poseDetectorHelper.detect(imageProxy)
            }
        }
    }

    private fun announceRulesCountdownIntroIfNeeded() {
        if (detectionState == DetectionState.EVALUATING || isRulesCountdownRunning || isRulesCountdownIntroPending) return
        isRulesCountdownIntroPending = true
        _binding?.tvRulesReadyHint?.text = "Evaluasi akan dimulai dalam 3 detik"
        realtimeTtsManager?.speakIfEligible(
            message = "Evaluasi akan dimulai dalam tiga detik",
            utteranceKey = "rules:countdown-intro",
            cooldownMs = 0L,
            minIntervalMs = 0L,
            flushQueue = true
        )
    }

    private fun beginRulesCountdownIfNeeded() {
        if (detectionState == DetectionState.EVALUATING || isRulesCountdownRunning) return
        val currentBinding = _binding ?: return

        detectionState = DetectionState.RULES_COUNTDOWN
        isRulesCountdownRunning = true
        isRulesCountdownIntroPending = false
        currentBinding.tvRulesCountdown.visibility = View.VISIBLE
        updateRulesCountdownUi(RULES_COUNTDOWN_SECONDS)
        speakRulesCountdownValue(RULES_COUNTDOWN_SECONDS)

        rulesCountdownTimer = object : CountDownTimer(RULES_COUNTDOWN_SECONDS * 1000L, 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                val secondsRemaining = ((millisUntilFinished + 999L) / 1000L).toInt().coerceAtLeast(1)
                updateRulesCountdownUi(secondsRemaining)
                speakRulesCountdownValue(secondsRemaining)
            }

            override fun onFinish() {
                isRulesCountdownRunning = false
                rulesCountdownTimer = null
                startEvaluationFromRulesOverlay()
            }
        }.start()
    }

    private fun startEvaluationFromRulesOverlay() {
        if (detectionState == DetectionState.EVALUATING) return
        cancelRulesCountdown()
        realtimeTtsManager?.stop()
        binding.layoutRulesOverlay.visibility = View.GONE
        detectionState = DetectionState.EVALUATING
        updateUi("BERSIAP", 0f, "Mulai evaluasi gerakan", true, null)
        startEvaluationRecordingIfNeeded()
    }

    private fun processResults(pose: PoseResult) {
        if (!isDetecting) return
        recordProcessedFrame()

        if (!pose.isValid()) {
            missingFrameCount++
            // Update UI langsung tanpa buffer agar skeleton hilang seketika
            updateUi("HILANG", 0f, getUndetectedFeedback(pose), false, null)
            return
        }
        missingFrameCount = 0

        // Motion detection
        val currentKeypoints = pose.rawKeypoints
        val movement = calculateMovement(lastKeypoints, currentKeypoints)
        lastKeypoints = currentKeypoints

        if (movement < MOVEMENT_THRESHOLD) {
            stillnessCount++
        } else {
            stillnessCount = 0
        }

        val ruleResult = engine.validate(pose)
        val result = if (exerciseType == ExerciseType.BICEP_CURL) {
            mergeBicepRuleAndClassifier(pose, ruleResult)
        } else {
            ruleResult
        }
        if (usesRepCompletionFlow()) {
            if (result.repCompleted) {
                repCounter.onValidatedRepCompleted(result.shouldCountRep)
            }
        } else {
            val metric = engine.calculateMetric(pose)
            if (result.isValid) {
                repCounter.onNewFrame(metric)
            }
        }

        confidenceSum += pose.score
        confidenceCount++
        val displayPose = buildDisplayPose(pose)
        lastEvaluationDisplayPose = displayPose
        val uiState = if (usesRepCompletionFlow()) {
            if (result.repCompleted && !result.shouldCountRep) {
                feedbackMap[result.feedback] = feedbackMap.getOrDefault(result.feedback, 0) + 1
            }
            buildRepUiState(result)
        } else {
            if (!result.isValid) {
                feedbackMap[result.feedback] = feedbackMap.getOrDefault(result.feedback, 0) + 1
            }
            DetectionUiState(
                label = if (result.isValid) "BENAR" else "SALAH",
                feedback = if (stillnessCount > STILLNESS_LIMIT) "Diam" else result.feedback,
                isCorrect = result.isValid
            )
        }

        maybeSpeakRealtimeFeedback(result, uiState)
        updateUi(uiState.label, pose.score, uiState.feedback, uiState.isCorrect, displayPose)
    }

    private fun buildDisplayPose(pose: PoseResult): PoseResult {
        if (exerciseType != ExerciseType.BICEP_CURL) {
            return pose
        }

        val raw = pose.rawKeypoints
        val smoothed = pose.keypoints
        val leftArmScore =
            raw[Keypoint.LEFT_SHOULDER].confidence +
            raw[Keypoint.LEFT_ELBOW].confidence +
            raw[Keypoint.LEFT_WRIST].confidence
        val rightArmScore =
            raw[Keypoint.RIGHT_SHOULDER].confidence +
            raw[Keypoint.RIGHT_ELBOW].confidence +
            raw[Keypoint.RIGHT_WRIST].confidence

        val useLeftArm = leftArmScore >= rightArmScore
        val activeArmIndexes = if (useLeftArm) {
            setOf(Keypoint.LEFT_SHOULDER, Keypoint.LEFT_ELBOW, Keypoint.LEFT_WRIST)
        } else {
            setOf(Keypoint.RIGHT_SHOULDER, Keypoint.RIGHT_ELBOW, Keypoint.RIGHT_WRIST)
        }

        if (kotlin.math.abs(leftArmScore - rightArmScore) < 0.35f) {
            val responsiveKeypoints = smoothed.mapIndexed { index, keypoint ->
                if (index in activeArmIndexes) blendActiveArmDisplayKeypoint(raw[index], keypoint) else keypoint
            }
            return pose.copy(keypoints = responsiveKeypoints)
        }

        val hideLeftArm = rightArmScore > leftArmScore
        val hiddenIndexes = if (hideLeftArm) {
            setOf(Keypoint.LEFT_ELBOW, Keypoint.LEFT_WRIST)
        } else {
            setOf(Keypoint.RIGHT_ELBOW, Keypoint.RIGHT_WRIST)
        }

        val displayKeypoints = smoothed.mapIndexed { index, keypoint ->
            when {
                index in hiddenIndexes -> keypoint.copy(confidence = 0f)
                index in activeArmIndexes -> blendActiveArmDisplayKeypoint(raw[index], keypoint)
                else -> keypoint
            }
        }

        return pose.copy(keypoints = displayKeypoints)
    }

    private fun usesRepCompletionFlow(): Boolean {
        return exerciseType == ExerciseType.BICEP_CURL ||
            exerciseType == ExerciseType.LATERAL_RAISE ||
            exerciseType == ExerciseType.SHOULDER_PRESS
    }

    private fun mergeBicepRuleAndClassifier(pose: PoseResult, ruleResult: RuleResult): RuleResult {
        val sequenceBuffer = bicepRepSequenceBuffer ?: return ruleResult
        val classifier = bicepSequenceClassifier ?: return ruleResult

        if (!pose.isValid() || !isBicepSideView(pose)) {
            sequenceBuffer.reset()
            return ruleResult
        }

        when (ruleResult.repStatus) {
            BicepRepStatus.IN_PROGRESS -> {
                sequenceBuffer.append(pose.rawKeypoints)
                return ruleResult
            }
            BicepRepStatus.REP_BAD -> {
                sequenceBuffer.reset()
                return ruleResult
            }
            BicepRepStatus.REP_GOOD -> {
                sequenceBuffer.append(pose.rawKeypoints)
                if (!ruleResult.shouldCountRep) {
                    sequenceBuffer.reset()
                    return ruleResult
                }

                val sequence = sequenceBuffer.buildResampledSequence(
                    targetLength = classifier.sequenceLength,
                    featureCount = classifier.featureCount
                )
                sequenceBuffer.reset()
                if (sequence == null) {
                    return ruleResult
                }

                val classification = runCatching { classifier.classify(sequence) }.getOrNull() ?: return ruleResult
                if (classification.isCorrect) {
                    return ruleResult
                }

                return ruleResult.copy(
                    isValid = false,
                    feedback = "Gerakan kurang tepat, ulangi dengan kontrol",
                    liveFeedback = "Gerakan kurang tepat, ulangi dengan kontrol",
                    repStatus = BicepRepStatus.REP_BAD,
                    shouldCountRep = false
                )
            }
            BicepRepStatus.IDLE -> {
                if (!sequenceBuffer.isEmpty()) {
                    sequenceBuffer.reset()
                }
                return ruleResult
            }
        }
    }

    private fun isBicepSideView(pose: PoseResult): Boolean {
        val keypoints = pose.rawKeypoints
        val leftShoulder = keypoints[Keypoint.LEFT_SHOULDER]
        val rightShoulder = keypoints[Keypoint.RIGHT_SHOULDER]
        if (leftShoulder.confidence <= 0.5f || rightShoulder.confidence <= 0.5f) {
            return false
        }
        return kotlin.math.abs(leftShoulder.x - rightShoulder.x) <= 0.15f
    }

    private fun getUndetectedFeedback(pose: PoseResult): String {
        val keypoints = pose.rawKeypoints
        val visiblePoints = keypoints.count { it.confidence > 0.3f }
        if (visiblePoints == 0) {
            return "Objek tidak terdeteksi"
        }

        return when (exerciseType) {
            ExerciseType.BICEP_CURL -> {
                val armVisible = hasVisibleArm(keypoints)
                if (!armVisible) "Lengan tidak terdeteksi" else "Posisi tubuh belum lengkap"
            }
            ExerciseType.LATERAL_RAISE, ExerciseType.SHOULDER_PRESS -> {
                val bothArmsVisible = hasVisibleUpperBody(keypoints)
                if (!bothArmsVisible) "Lengan tidak terdeteksi" else "Posisi tubuh belum lengkap"
            }
            ExerciseType.SQUAT -> {
                val legsVisible = hasVisibleLegs(keypoints)
                if (!legsVisible) "Kaki tidak terdeteksi" else "Posisi tubuh belum lengkap"
            }
        }
    }

    private fun hasVisibleArm(keypoints: List<Keypoint>): Boolean {
        val leftArmVisible =
            keypoints[Keypoint.LEFT_SHOULDER].confidence > 0.3f &&
            keypoints[Keypoint.LEFT_ELBOW].confidence > 0.3f &&
            keypoints[Keypoint.LEFT_WRIST].confidence > 0.3f

        val rightArmVisible =
            keypoints[Keypoint.RIGHT_SHOULDER].confidence > 0.3f &&
            keypoints[Keypoint.RIGHT_ELBOW].confidence > 0.3f &&
            keypoints[Keypoint.RIGHT_WRIST].confidence > 0.3f

        return leftArmVisible || rightArmVisible
    }

    private fun hasVisibleUpperBody(keypoints: List<Keypoint>): Boolean {
        return keypoints[Keypoint.LEFT_SHOULDER].confidence > 0.3f &&
            keypoints[Keypoint.RIGHT_SHOULDER].confidence > 0.3f &&
            hasVisibleArm(keypoints)
    }

    private fun hasVisibleLegs(keypoints: List<Keypoint>): Boolean {
        val leftLegVisible =
            keypoints[Keypoint.LEFT_HIP].confidence > 0.3f &&
            keypoints[Keypoint.LEFT_KNEE].confidence > 0.3f &&
            keypoints[Keypoint.LEFT_ANKLE].confidence > 0.3f

        val rightLegVisible =
            keypoints[Keypoint.RIGHT_HIP].confidence > 0.3f &&
            keypoints[Keypoint.RIGHT_KNEE].confidence > 0.3f &&
            keypoints[Keypoint.RIGHT_ANKLE].confidence > 0.3f

        return leftLegVisible || rightLegVisible
    }

    private fun calculateMovement(oldKps: List<Keypoint>?, newKps: List<Keypoint>): Float {
        if (oldKps == null || oldKps.size != newKps.size) return 1f
        var totalDist = 0f
        var count = 0
        for (i in newKps.indices) {
            if (newKps[i].confidence > 0.3f && oldKps[i].confidence > 0.3f) {
                val dx = newKps[i].x - oldKps[i].x
                val dy = newKps[i].y - oldKps[i].y
                totalDist += kotlin.math.sqrt(dx * dx + dy * dy)
                count++
            }
        }
        return if (count > 0) totalDist / count else 0f
    }

    private fun buildRepUiState(result: RuleResult): DetectionUiState {
        val now = SystemClock.elapsedRealtime()

        if (result.repStatus == BicepRepStatus.REP_GOOD || result.repStatus == BicepRepStatus.REP_BAD) {
            repResultDisplayUntil = now + REP_RESULT_DISPLAY_MS
            repResultLabel = if (result.repStatus == BicepRepStatus.REP_GOOD) "BENAR" else "SALAH"
            repResultFeedback = result.feedback
            repResultCorrect = result.repStatus == BicepRepStatus.REP_GOOD
        }

        if (now < repResultDisplayUntil) {
            return DetectionUiState(
                label = repResultLabel,
                feedback = repResultFeedback,
                isCorrect = repResultCorrect
            )
        }

        if (!result.isValid && !result.repCompleted) {
            return DetectionUiState(
                label = "SALAH",
                feedback = result.liveFeedback,
                isCorrect = false
            )
        }

        return when (result.repStatus) {
            BicepRepStatus.IN_PROGRESS -> DetectionUiState(
                label = "GERAKAN",
                feedback = result.liveFeedback,
                isCorrect = true
            )
            BicepRepStatus.IDLE -> DetectionUiState(
                label = "SIAP",
                feedback = result.liveFeedback,
                isCorrect = true
            )
            BicepRepStatus.REP_GOOD -> DetectionUiState(
                label = "BENAR",
                feedback = result.feedback,
                isCorrect = true
            )
            BicepRepStatus.REP_BAD -> DetectionUiState(
                label = "SALAH",
                feedback = result.feedback,
                isCorrect = false
            )
        }
    }

    private fun resetRepResultState() {
        repResultDisplayUntil = 0L
        repResultLabel = "SIAP"
        repResultFeedback = "Siap untuk repetisi berikutnya"
        repResultCorrect = true
    }

    private fun updateUi(label: String, confidence: Float, feedback: String, isCorrect: Boolean, pose: PoseResult?) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastUiUpdateTime < LIVE_UI_UPDATE_INTERVAL_MS) return
        lastUiUpdateTime = now

        requireActivity().runOnUiThread {
            val b = _binding ?: return@runOnUiThread
            b.tvLabel.text = label
            b.tvLabel.setTextColor(if (isCorrect) Color.parseColor("#22C55E") else Color.parseColor("#EF4444"))
            
            val pct = (confidence * 100).toInt()
            b.tvConfidence.text = "$pct%"
            b.progressConfidence.progress = pct
            
            b.tvReps.text = repCounter.getRepCount().toString()
            b.tvFeedback.text = feedback
            b.overlayView.updatePose(pose, isCorrect)
        }
    }

    private fun resetLivePerformanceMetrics() {
        val now = SystemClock.elapsedRealtime()
        lastProcessedFpsTime = now
        processedFrameCount = 0
        lastInferenceRequestTime = 0L
        lastInferenceStartTime = 0L
        smoothedInferenceDurationMs = DEFAULT_LIVE_INFERENCE_INTERVAL_MS.toFloat()
        currentInferenceIntervalMs = DEFAULT_LIVE_INFERENCE_INTERVAL_MS
        lastUiUpdateTime = 0L
    }

    private fun recordProcessedFrame() {
        val now = SystemClock.elapsedRealtime()
        processedFrameCount++

        if (lastInferenceStartTime > 0L) {
            val inferenceDurationMs = (now - lastInferenceStartTime).coerceAtLeast(1L).toFloat()
            smoothedInferenceDurationMs =
                (smoothedInferenceDurationMs * 0.75f) + (inferenceDurationMs * 0.25f)
            currentInferenceIntervalMs = smoothedInferenceDurationMs
                .roundToInt()
                .toLong()
                .coerceIn(MIN_LIVE_INFERENCE_INTERVAL_MS, MAX_LIVE_INFERENCE_INTERVAL_MS)
        }
        lastInferenceStartTime = 0L

        if (now - lastProcessedFpsTime < 1000L) return

        val fps = processedFrameCount * 1000f / (now - lastProcessedFpsTime)
        processedFrameCount = 0
        lastProcessedFpsTime = now
        requireActivity().runOnUiThread {
            _binding?.tvFps?.text = "${fps.toInt()} FPS"
        }
    }

    private fun speakRulesGuidanceIfNeeded() {
        if (hasSpokenRulesGuidance) return
        hasSpokenRulesGuidance = true
        val guidanceSpeech = buildString {
            append("Panduan evaluasi. ")
            append("Cari tempat yang cukup terang supaya tubuh terlihat jelas. ")
            append("Atur jarak sekitar satu setengah sampai dua setengah meter, lalu sesuaikan sampai kepala dan kaki tetap masuk kamera. ")
            append("Pastikan tubuh terlihat utuh dari kepala sampai kaki. ")
            append("Letakkan handphone tegak dan stabil, sebaiknya gunakan tripod atau penyangga saat evaluasi. ")
            append("Lakukan gerakan sesuai latihan yang dipilih, lalu ikuti arahan di layar. ")
        }
        realtimeTtsManager?.speakIfEligible(
            message = guidanceSpeech,
            utteranceKey = "rules:guidance",
            cooldownMs = 5_000L,
            minIntervalMs = 0L,
            flushQueue = true
        )
    }

    private fun updateRulesCountdownUi(secondsRemaining: Int) {
        val currentBinding = _binding ?: return
        currentBinding.tvRulesReadyHint.text = "Evaluasi akan dimulai dalam $secondsRemaining detik"
        currentBinding.tvRulesCountdown.text = secondsRemaining.toString()
        if (lastAnimatedRulesCountdownValue == secondsRemaining) return
        lastAnimatedRulesCountdownValue = secondsRemaining

        currentBinding.tvRulesCountdown.apply {
            alpha = 0f
            scaleX = 0.72f
            scaleY = 0.72f
            animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(260L)
                .start()
        }
    }

    private fun speakRulesCountdownValue(secondsRemaining: Int) {
        if (lastSpokenRulesCountdownValue == secondsRemaining) return
        lastSpokenRulesCountdownValue = secondsRemaining

        val spokenNumber = countdownNumberSpeech(secondsRemaining)
        realtimeTtsManager?.speakIfEligible(
            message = spokenNumber,
            utteranceKey = "rules:countdown:$secondsRemaining",
            cooldownMs = 0L,
            minIntervalMs = 0L,
            flushQueue = true
        )
    }

    private fun countdownNumberSpeech(secondsRemaining: Int): String {
        return when (secondsRemaining) {
            10 -> "sepuluh"
            9 -> "sembilan"
            8 -> "delapan"
            7 -> "tujuh"
            6 -> "enam"
            5 -> "lima"
            4 -> "empat"
            3 -> "tiga"
            2 -> "dua"
            1 -> "satu"
            else -> secondsRemaining.toString()
        }
    }

    private fun cancelRulesCountdown() {
        rulesCountdownTimer?.cancel()
        rulesCountdownTimer = null
        isRulesCountdownRunning = false
        isRulesCountdownIntroPending = false
        lastAnimatedRulesCountdownValue = -1
        lastSpokenRulesCountdownValue = -1
    }

    private fun maybeSpeakRealtimeFeedback(result: RuleResult, uiState: DetectionUiState) {
        if (detectionState != DetectionState.EVALUATING) return

        if (usesRepCompletionFlow() && result.repCompleted) {
            val repSpeech = if (result.shouldCountRep) {
                "Gerakan benar"
            } else {
                val correction = mapFeedbackToSpeech(result.feedback)
                if (correction != null) {
                    "Gerakan salah. $correction"
                } else {
                    "Gerakan salah"
                }
            }

            realtimeTtsManager?.speakIfEligible(
                message = repSpeech,
                utteranceKey = "rep:${result.shouldCountRep}:${normalizeSpeechKey(result.feedback)}",
                cooldownMs = 1200L,
                minIntervalMs = 650L
            )
            return
        }

        val speech = when {
            uiState.label == "HILANG" -> mapFeedbackToSpeech(uiState.feedback)
            !uiState.isCorrect -> mapFeedbackToSpeech(uiState.feedback)
            usesRepCompletionFlow() && uiState.label == "GERAKAN" ->
                mapLiveCoachingToSpeech(uiState.feedback)
            else -> null
        } ?: return

        realtimeTtsManager?.speakIfEligible(
            message = speech,
            utteranceKey = "feedback:${normalizeSpeechKey(speech)}",
            cooldownMs = 2800L,
            minIntervalMs = 1100L
        )
    }

    private fun mapFeedbackToSpeech(feedback: String): String? {
        return when (feedback.trim()) {
            "Lengan tidak terdeteksi" -> "Lengan tidak terdeteksi"
            "Objek tidak terdeteksi" -> "Objek tidak terdeteksi"
            "Harus menghadap ke samping" -> "Harus menghadap ke samping"
            "Hadapkan tubuh ke depan kamera" -> "Hadapkan tubuh ke depan kamera"
            "Tempo terlalu cepat, perlambat gerakan" -> "Tempo terlalu cepat, perlambat gerakan"
            "Jaga siku tetap diam di samping tubuh" -> "Jaga siku tetap diam di samping tubuh"
            "Jaga tubuh tetap tegak, jangan terlalu bergoyang" -> "Jaga tubuh tetap tegak, jangan terlalu bergoyang"
            "Angkat beban lebih tinggi dan jaga tubuh tetap tegak" -> "Angkat beban lebih tinggi dan jaga tubuh tetap tegak"
            "Gerakan kurang tepat, ulangi dengan kontrol" -> "Gerakan kurang tepat, ulangi dengan kontrol"
            "Jaga dorongan kedua lengan tetap simetris" -> "Jaga dorongan kedua lengan tetap simetris"
            "Dorong beban lurus ke atas sampai tangan hampir lurus" -> "Dorong beban lurus ke atas sampai tangan hampir lurus"
            "Dorong beban sampai hampir lurus dan jaga punggung tetap stabil" -> "Dorong beban sampai hampir lurus dan jaga punggung tetap stabil"
            "Pastikan kedua lengan terlihat jelas" -> "Pastikan kedua lengan terlihat jelas"
            "Angkat beban sedikit lebih tinggi" -> "Angkat beban sedikit lebih tinggi"
            "Jaga siku tetap sedikit menekuk" -> "Jaga siku tetap sedikit menekuk"
            "Angkat sedikit lagi sampai sejajar bahu" -> "Angkat sedikit lagi sampai sejajar bahu"
            "Angkat siku sejajar bahu, jangan lebih tinggi dari bahu" -> "Angkat siku sejajar bahu, jangan lebih tinggi dari bahu"
            "Angkat lengan setinggi bahu dan hindari tubuh condong" -> "Angkat lengan setinggi bahu dan hindari tubuh condong"
            "Pimpin gerakan dengan siku, jangan pergelangan tangan" -> "Pimpin gerakan dengan siku, jangan pergelangan tangan"
            "Jangan angkat bahu saat mengangkat beban" -> "Jangan angkat bahu saat mengangkat beban"
            "Jaga tubuh tetap stabil, jangan bergoyang" -> "Jaga tubuh tetap stabil, jangan bergoyang"
            "Angkat beban ke samping serong, jangan terlalu ke pinggir" -> "Angkat beban ke samping serong, jangan terlalu ke pinggir"
            "Jaga kedua lengan tetap seimbang" -> "Jaga kedua lengan tetap seimbang"
            "Kaki tidak terdeteksi" -> "Kaki tidak terdeteksi"
            "Turunkan pinggul lebih dalam dan jaga tubuh tetap stabil" -> "Turunkan pinggul lebih dalam dan jaga tubuh tetap stabil"
            "Posisi tubuh belum lengkap" -> "Posisi tubuh belum lengkap"
            "Pastikan tubuh terlihat jelas di kamera" -> "Pastikan tubuh terlihat jelas di kamera"
            else -> null
        }
    }

    private fun mapLiveCoachingToSpeech(feedback: String): String? {
        return when (feedback.trim()) {
            "Tempo terlalu cepat, perlambat gerakan" -> feedback
            "Jaga siku tetap diam di samping tubuh" -> feedback
            "Jaga tubuh tetap tegak, jangan terlalu bergoyang" -> feedback
            "Angkat beban lebih tinggi dan jaga tubuh tetap tegak" -> feedback
            "Jaga dorongan kedua lengan tetap simetris" -> feedback
            "Dorong beban lurus ke atas sampai tangan hampir lurus" -> feedback
            "Dorong beban sampai hampir lurus dan jaga punggung tetap stabil" -> feedback
            "Angkat beban sedikit lebih tinggi" -> feedback
            "Jaga siku tetap sedikit menekuk" -> feedback
            "Angkat sedikit lagi sampai sejajar bahu" -> feedback
            "Angkat siku sejajar bahu, jangan lebih tinggi dari bahu" -> feedback
            "Angkat lengan setinggi bahu dan hindari tubuh condong" -> feedback
            "Pimpin gerakan dengan siku, jangan pergelangan tangan" -> feedback
            "Jangan angkat bahu saat mengangkat beban" -> feedback
            "Jaga tubuh tetap stabil, jangan bergoyang" -> feedback
            "Angkat beban ke samping serong, jangan terlalu ke pinggir" -> feedback
            "Jaga kedua lengan tetap seimbang" -> feedback
            "Turunkan pinggul lebih dalam dan jaga tubuh tetap stabil" -> feedback
            else -> null
        }
    }

    private fun normalizeSpeechKey(message: String): String {
        return message
            .trim()
            .lowercase(Locale.forLanguageTag("id-ID"))
            .replace(Regex("\\s+"), "_")
    }

    private data class DetectionUiState(
        val label: String,
        val feedback: String,
        val isCorrect: Boolean
    )

    private fun stopAndSaveSession() {
        if (!isDetecting || isSavingSession) return

        isDetecting = false
        isSavingSession = true
        _binding?.btnStop?.isEnabled = false
        if (::poseDetectorHelper.isInitialized) poseDetectorHelper.close()

        val duration = (SystemClock.elapsedRealtime() - sessionStartTime) / 1000
        val avgConf = if (confidenceCount > 0) confidenceSum / confidenceCount else 0f
        val mostFrequentFeedback = feedbackMap.maxByOrNull { it.value }?.key
        val feedbackSummary = buildFeedbackSummary()

        scope.launch {
            val finalizedVideoPath = stopEvaluationRecordingAndFinalize()
            val db = GymDatabase.getInstance(requireContext())
            val repo = WorkoutRepository(db.workoutSessionDao(), requireContext().applicationContext)
            repo.insertSession(WorkoutSession(
                totalReps = repCounter.getRepCount(),
                averageConfidence = avgConf,
                durationSeconds = duration,
                exerciseType = exerciseType.name,
                mostFrequentFeedback = mostFrequentFeedback,
                feedbackSummary = feedbackSummary,
                evaluationVideoPath = finalizedVideoPath
            ))
            activity?.runOnUiThread {
                if (isAdded) {
                    if (::cameraManager.isInitialized) cameraManager.release()
                    showEvaluationSavedDialog(
                        reps = repCounter.getRepCount(),
                        averageConfidence = avgConf
                    )
                }
            }
        }
    }

    private fun showEvaluationSavedDialog(reps: Int, averageConfidence: Float) {
        if (!isAdded) return

        val dialogBinding = DialogEvaluationSavedBinding.inflate(layoutInflater)
        MaterialSymbols.applyToTree(dialogBinding.root)
        dialogBinding.tvDialogExercise.text = exerciseType.displayName
        dialogBinding.tvDialogReps.text = "$reps Repetisi"
        dialogBinding.tvDialogConfidence.text =
            "Rata-rata akurasi ${(averageConfidence * 100f).toInt()}%"

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setView(dialogBinding.root)
            .setCancelable(false)
            .create()

        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        dialogBinding.btnDialogLater.setOnClickListener {
            dialog.dismiss()
            findNavController().navigateUp()
        }

        dialogBinding.btnDialogHistory.setOnClickListener {
            dialog.dismiss()
            val navController = findNavController()
            val openedHistory = runCatching {
                navController
                    .getBackStackEntry(com.modul.gymai.R.id.dashboardFragment)
                    .savedStateHandle
                    .set("openHistoryTab", true)
                navController.popBackStack(com.modul.gymai.R.id.dashboardFragment, false)
            }.getOrDefault(false)

            if (!openedHistory) {
                navController.navigateUp()
            }
        }

        dialog.setOnCancelListener {
            if (isAdded) {
                findNavController().navigateUp()
            }
        }

        dialog.show()

        dialogBinding.root.apply {
            alpha = 0f
            scaleX = 0.92f
            scaleY = 0.92f
            translationY = 36f
            animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .translationY(0f)
                .setDuration(260L)
                .start()
        }

        dialogBinding.btnDialogHistory.apply {
            alpha = 0f
            translationY = 18f
            animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(100L)
                .setDuration(220L)
                .start()
        }

        dialogBinding.btnDialogLater.apply {
            alpha = 0f
            translationY = 18f
            animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(140L)
                .setDuration(220L)
                .start()
        }
    }

    private fun buildFeedbackSummary(): String {
        if (feedbackMap.isEmpty()) {
            return "Evaluasi sesi:\n- Gerakan dominan dinilai baik, tidak ada koreksi utama yang menonjol."
        }

        val totalIssues = feedbackMap.values.sum().coerceAtLeast(1)
        val sortedFeedback = feedbackMap.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })

        val summaryLines = sortedFeedback.take(3).map { entry ->
            val percentage = ((entry.value.toFloat() / totalIssues.toFloat()) * 100f).roundToInt()
            "- ${entry.key} (${entry.value}x, ${percentage}%)"
        }.toMutableList()

        val remainingCount = sortedFeedback.size - summaryLines.size
        if (remainingCount > 0) {
            summaryLines += "- ${remainingCount} feedback lain juga muncul selama sesi"
        }

        return buildString {
            append("Evaluasi sesi:")
            append('\n')
            append(summaryLines.joinToString("\n"))
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        isDetecting = false
        stopEvaluationFrameCapture()
        cancelRulesCountdown()
        if (::poseDetectorHelper.isInitialized) poseDetectorHelper.close()
        if (!isSavingSession) {
            runCatching { evaluationVideoRecorder?.discard() }
        }
        evaluationVideoRecorder = null
        if (!isSavingSession) {
            if (::cameraManager.isInitialized) {
                cameraManager.cancelEvaluationRecording()
                cameraManager.release()
            }
        } else if (::cameraManager.isInitialized) {
            cameraManager.release()
        }
        realtimeTtsManager?.setOnUtteranceDoneListener(null)
        realtimeTtsManager?.shutdown()
        realtimeTtsManager = null
        runCatching { bicepSequenceClassifier?.close() }
        bicepSequenceClassifier = null
        bicepRepSequenceBuffer = null
        _binding = null
    }

    override fun onResume() {
        super.onResume()
        if (!isDetecting || detectionState != DetectionState.RULES_OVERLAY || hasSpokenRulesGuidance) return
        _binding?.layoutRulesOverlay?.visibility = View.VISIBLE
        _binding?.tvRulesReadyHint?.text = "Panduan evaluasi sedang dibacakan, tunggu sebentar"
        speakRulesGuidanceIfNeeded()
    }

    override fun onStop() {
        super.onStop()
        if (detectionState != DetectionState.EVALUATING) {
            cancelRulesCountdown()
            detectionState = DetectionState.RULES_OVERLAY
            hasSpokenRulesGuidance = false
            _binding?.tvRulesCountdown?.visibility = View.GONE
            _binding?.tvRulesReadyHint?.text = "Panduan evaluasi akan diputar ulang saat halaman dibuka kembali"
        }
        realtimeTtsManager?.stop()
    }

    private fun startEvaluationRecordingIfNeeded() {
        if (hasRequestedEvaluationRecordingStart || hasEvaluationRecordingFailed) return
        val currentBinding = _binding ?: return

        val externalDir = requireContext().getExternalFilesDir("evaluations")
        val baseDir = externalDir ?: File(requireContext().filesDir, "evaluations")
        baseDir.mkdirs()
        val sourceWidth = currentBinding.previewView.width.takeIf { it > 0 } ?: currentBinding.overlayView.width
        val sourceHeight = currentBinding.previewView.height.takeIf { it > 0 } ?: currentBinding.overlayView.height
        if (sourceWidth <= 0 || sourceHeight <= 0) {
            hasEvaluationRecordingFailed = true
            return
        }

        val recorder = EvaluationVideoRecorder(
            baseDir = baseDir,
            targetFps = (1000L / EVALUATION_RECORDING_FRAME_INTERVAL_MS).toInt().coerceAtLeast(6),
            maxDimension = 720
        )
        val requestedPath = recorder.startRecording(exerciseType.name, sourceWidth, sourceHeight)
        if (requestedPath.isNullOrBlank()) {
            hasEvaluationRecordingFailed = true
            evaluationVideoRecorder = null
            evaluationVideoPath = null
            return
        }

        evaluationVideoRecorder = recorder
        evaluationVideoPath = requestedPath
        hasRequestedEvaluationRecordingStart = true
        startEvaluationFrameCapture()
    }

    private suspend fun stopEvaluationRecordingAndFinalize(): String? {
        stopEvaluationFrameCapture()
        val recorder = evaluationVideoRecorder ?: return evaluationVideoPath
        val finalizedPath = runCatching { recorder.stopAndFinalize() }.getOrNull()
        evaluationVideoRecorder = null
        if (!finalizedPath.isNullOrBlank()) {
            evaluationVideoPath = finalizedPath
            return finalizedPath
        }
        evaluationVideoPath = null
        return null
    }

    private fun startEvaluationFrameCapture() {
        val currentBinding = _binding ?: return
        stopEvaluationFrameCapture()
        currentBinding.previewView.post(evaluationFrameCaptureRunnable)
    }

    private fun stopEvaluationFrameCapture() {
        _binding?.previewView?.removeCallbacks(evaluationFrameCaptureRunnable)
    }

    private fun captureEvaluationFrame() {
        val currentBinding = _binding ?: return
        val recorder = evaluationVideoRecorder ?: return
        if (!recorder.isActive()) return

        val previewBitmap = currentBinding.previewView.bitmap ?: return
        val overlayWidth = currentBinding.overlayView.width
        val overlayHeight = currentBinding.overlayView.height
        val targetWidth = overlayWidth.takeIf { it > 0 } ?: previewBitmap.width
        val targetHeight = overlayHeight.takeIf { it > 0 } ?: previewBitmap.height

        val fullFrameBitmap = Bitmap.createBitmap(
            targetWidth.coerceAtLeast(1),
            targetHeight.coerceAtLeast(1),
            Bitmap.Config.ARGB_8888
        )
        val canvas = Canvas(fullFrameBitmap)
        canvas.drawColor(Color.BLACK)
        val previewRect = android.graphics.Rect(0, 0, targetWidth, targetHeight)
        canvas.drawBitmap(previewBitmap, null, previewRect, null)
        currentBinding.overlayView.draw(canvas)
        previewBitmap.recycle()

        val cropRect = calculateRecordedContentRect(targetWidth, targetHeight, lastEvaluationDisplayPose)
        val croppedBitmap = if (
            cropRect.width() in 1 until targetWidth ||
            cropRect.height() in 1 until targetHeight
        ) {
            Bitmap.createBitmap(fullFrameBitmap, cropRect.left, cropRect.top, cropRect.width(), cropRect.height())
        } else {
            fullFrameBitmap
        }

        if (croppedBitmap !== fullFrameBitmap) {
            fullFrameBitmap.recycle()
        }

        recorder.recordFrame(croppedBitmap, System.nanoTime())
    }

    private fun calculateRecordedContentRect(
        viewWidth: Int,
        viewHeight: Int,
        pose: PoseResult?
    ): Rect {
        if (viewWidth <= 0 || viewHeight <= 0) {
            return Rect(0, 0, 1, 1)
        }

        val sourceWidth = pose?.sourceWidth?.takeIf { it > 0f } ?: viewWidth.toFloat()
        val sourceHeight = pose?.sourceHeight?.takeIf { it > 0f } ?: viewHeight.toFloat()
        val sourceAspect = sourceWidth / sourceHeight
        val viewAspect = viewWidth.toFloat() / viewHeight.toFloat()

        return if (sourceAspect > viewAspect) {
            val contentHeight = (viewWidth / sourceAspect).toInt().coerceAtLeast(1)
            val top = ((viewHeight - contentHeight) / 2f).toInt().coerceAtLeast(0)
            Rect(0, top, viewWidth, (top + contentHeight).coerceAtMost(viewHeight))
        } else {
            val contentWidth = (viewHeight * sourceAspect).toInt().coerceAtLeast(1)
            val left = ((viewWidth - contentWidth) / 2f).toInt().coerceAtLeast(0)
            Rect(left, 0, (left + contentWidth).coerceAtMost(viewWidth), viewHeight)
        }
    }

    private fun blendActiveArmDisplayKeypoint(raw: Keypoint, smoothed: Keypoint): Keypoint {
        if (raw.confidence < 0.35f || smoothed.confidence < 0.35f) {
            return smoothed
        }

        val dx = raw.x - smoothed.x
        val dy = raw.y - smoothed.y
        val movement = kotlin.math.sqrt(dx * dx + dy * dy)

        if (movement < 0.0032f) {
            return smoothed.copy(confidence = raw.confidence)
        }

        val alpha = when {
            movement < 0.01f -> 0.2f
            movement < 0.022f -> 0.36f
            movement < 0.04f -> 0.56f
            else -> 0.76f
        }

        return Keypoint(
            x = smoothed.x + ((raw.x - smoothed.x) * alpha),
            y = smoothed.y + ((raw.y - smoothed.y) * alpha),
            confidence = raw.confidence
        )
    }
}
