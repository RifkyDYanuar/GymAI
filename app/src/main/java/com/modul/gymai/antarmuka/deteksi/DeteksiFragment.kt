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
import com.modul.gymai.ml.LateralRaiseRepSequenceBuffer
import com.modul.gymai.ml.LateralRaiseSequenceClassifier
import com.modul.gymai.ml.ShoulderPressRepSequenceBuffer
import com.modul.gymai.ml.ShoulderPressSequenceClassifier
import com.modul.gymai.ml.SquatRepSequenceBuffer
import com.modul.gymai.ml.SquatSequenceClassifier
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
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.roundToInt

class DeteksiFragment : Fragment() {

    companion object {
        private const val TAG = "DeteksiFragment"
        private const val ARG_EXERCISE_TYPE = "exerciseType"
        private const val MOVEMENT_THRESHOLD = 0.005f // Sensitivity for "Diam"
        private const val STILLNESS_LIMIT = 30 // Frames before showing "Diam"
        private const val REP_RESULT_DISPLAY_MS = 1200L
        private const val RULES_COUNTDOWN_SECONDS = 3
        private const val LIVE_UI_UPDATE_INTERVAL_MS = 66L          // ~15fps UI update, ringan di main thread
        private const val MIN_LIVE_INFERENCE_INTERVAL_MS = 33L        // min ~30fps inference
        private const val DEFAULT_LIVE_INFERENCE_INTERVAL_MS = 50L    // default ~20fps
        private const val MAX_LIVE_INFERENCE_INTERVAL_MS = 150L       // beri napas lebih di HP lambat
        private const val EVALUATION_RECORDING_FPS = 8
        private const val EVALUATION_RECORDING_FRAME_INTERVAL_MS = 1000L / EVALUATION_RECORDING_FPS
        private const val EVALUATION_RECORDING_MAX_DIMENSION = 540
        private const val ENABLE_RULES_OVERLAY = false
        private const val ENABLE_SQUAT_CNN1D = true
        private const val SQUAT_SIDE_TO_DIAGONAL_SHOULDER_MAX = 0.18f
        private const val SQUAT_BODY_SIDE_CONFIDENCE_MIN = 0.5f
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
    // State untuk overlay HUD di video rekaman
    @Volatile private var recordingLabel: String = "SIAP"
    @Volatile private var recordingFeedback: String = ""
    @Volatile private var recordingIsCorrect: Boolean = true
    // Pre-alokasi bitmap untuk menghindari GC per frame
    private var recordingBitmap: Bitmap? = null
    private var recordingSourceRect: Rect? = null
    private val recordingDrawExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
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
    private var lateralRaiseSequenceClassifier: LateralRaiseSequenceClassifier? = null
    private var lateralRaiseRepSequenceBuffer: LateralRaiseRepSequenceBuffer? = null
    private var shoulderPressSequenceClassifier: ShoulderPressSequenceClassifier? = null
    private var shoulderPressRepSequenceBuffer: ShoulderPressRepSequenceBuffer? = null
    private var squatSequenceClassifier: SquatSequenceClassifier? = null
    private var squatRepSequenceBuffer: SquatRepSequenceBuffer? = null
    private var cnnDebugIndicatorText = "CNN1D TEST: --"
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
        binding.tvCnnIndicator.visibility = View.GONE
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

        if (exerciseType == ExerciseType.LATERAL_RAISE) {
            lateralRaiseRepSequenceBuffer = LateralRaiseRepSequenceBuffer()
            if (lateralRaiseSequenceClassifier == null) {
                runCatching {
                    lateralRaiseSequenceClassifier = LateralRaiseSequenceClassifier(requireContext())
                }.onFailure { error ->
                    Log.e(TAG, "Lateral Raise CNN classifier failed to load", error)
                    lateralRaiseSequenceClassifier = null
                }
            }
        } else {
            lateralRaiseRepSequenceBuffer = null
        }

        if (exerciseType == ExerciseType.SHOULDER_PRESS) {
            shoulderPressRepSequenceBuffer = ShoulderPressRepSequenceBuffer()
            if (shoulderPressSequenceClassifier == null) {
                runCatching {
                    shoulderPressSequenceClassifier = ShoulderPressSequenceClassifier(requireContext())
                }.onFailure { error ->
                    Log.e(TAG, "Shoulder Press CNN classifier failed to load", error)
                    shoulderPressSequenceClassifier = null
                }
            }
        } else {
            shoulderPressRepSequenceBuffer = null
        }

        if (exerciseType == ExerciseType.SQUAT) {
            squatRepSequenceBuffer = SquatRepSequenceBuffer()
            if (!ENABLE_SQUAT_CNN1D) {
                squatSequenceClassifier = null
                updateCnnDebugIndicator("CNN1D TEST: squat nonaktif, mode rule-based")
            } else if (squatSequenceClassifier == null) {
                runCatching {
                    squatSequenceClassifier = SquatSequenceClassifier(requireContext())
                    updateCnnDebugIndicator("CNN1D TEST: model squat siap")
                }.onFailure { error ->
                    Log.e(TAG, "Squat CNN classifier failed to load", error)
                    squatSequenceClassifier = null
                    updateCnnDebugIndicator("CNN1D TEST: model gagal dimuat")
                }
            } else {
                updateCnnDebugIndicator("CNN1D TEST: model squat siap")
            }
        } else {
            squatRepSequenceBuffer = null
            if (exerciseType != ExerciseType.BICEP_CURL) {
                updateCnnDebugIndicator("CNN1D TEST: --")
            }
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
        recordingSourceRect = null
        hasSpokenRulesGuidance = false
        isSavingSession = false
        bicepRepSequenceBuffer?.reset()
        lateralRaiseRepSequenceBuffer?.reset()
        shoulderPressRepSequenceBuffer?.reset()
        squatRepSequenceBuffer?.reset()
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
        if (exerciseType == ExerciseType.SQUAT) {
            updateCnnDebugIndicator(
                if (!ENABLE_SQUAT_CNN1D) {
                    "CNN1D TEST: squat nonaktif, mode rule-based"
                } else if (squatSequenceClassifier != null) {
                    "CNN1D TEST: standby, tunggu side view"
                } else {
                    "CNN1D TEST: model belum siap"
                }
            )
        }

        if (ENABLE_RULES_OVERLAY) {
            detectionState = DetectionState.RULES_OVERLAY
            binding.layoutRulesOverlay.visibility = View.VISIBLE
            binding.tvRulesCountdown.visibility = View.GONE
            binding.tvRulesReadyHint.text = "Panduan evaluasi sedang dibacakan, tunggu sebentar"
            binding.tvLabel.text = "SIAP"
            binding.tvFeedback.text = "Dengarkan panduan evaluasi sebelum mulai"
            speakRulesGuidanceIfNeeded()
        } else {
            binding.layoutRulesOverlay.visibility = View.GONE
            binding.tvRulesCountdown.visibility = View.GONE
            startEvaluationFromRulesOverlay()
        }
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
            if (exerciseType == ExerciseType.SQUAT) {
                updateCnnDebugIndicator("CNN1D TEST: menunggu pose valid")
            }
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
        val result = when (exerciseType) {
            ExerciseType.BICEP_CURL -> mergeBicepRuleAndClassifier(pose, ruleResult)
            ExerciseType.LATERAL_RAISE -> mergeLateralRaiseRuleAndClassifier(pose, ruleResult)
            ExerciseType.SHOULDER_PRESS -> mergeShoulderPressRuleAndClassifier(pose, ruleResult)
            ExerciseType.SQUAT -> mergeSquatRuleAndClassifier(pose, ruleResult)
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
            if (result.repCompleted && !result.shouldCountRep && !result.isPositionIssue) {
                feedbackMap[result.feedback] = feedbackMap.getOrDefault(result.feedback, 0) + 1
            }
            buildRepUiState(result)
        } else {
            if (!result.isValid && !result.isPositionIssue) {
                // Hanya catat ke feedbackMap jika bukan masalah posisi/orientasi
                feedbackMap[result.feedback] = feedbackMap.getOrDefault(result.feedback, 0) + 1
            }
            val label = when {
                result.isValid -> "BENAR"
                result.isPositionIssue -> "POSISI"  // orientasi/posisi tubuh salah
                else -> "SALAH"
            }
            DetectionUiState(
                label = label,
                feedback = if (stillnessCount > STILLNESS_LIMIT) "Diam" else result.feedback,
                isCorrect = result.isValid,
                isPositionIssue = result.isPositionIssue
            )
        }

        maybeSpeakRealtimeFeedback(result, uiState)
        updateUi(uiState.label, pose.score, uiState.feedback, uiState.isCorrect, displayPose, uiState.isPositionIssue)
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
        return exerciseType == ExerciseType.SQUAT ||
            exerciseType == ExerciseType.BICEP_CURL ||
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
                val clarifiedFeedback = buildBicepModelFeedback(ruleResult.feedback, false)
                return ruleResult.copy(
                    isValid = false,
                    feedback = clarifiedFeedback,
                    liveFeedback = clarifiedFeedback,
                    repStatus = BicepRepStatus.REP_BAD,
                    repCompleted = true,
                    shouldCountRep = false
                )
            }
            BicepRepStatus.REP_GOOD -> {
                sequenceBuffer.append(pose.rawKeypoints)

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
                    return ruleResult.copy(
                        isValid = true,
                        feedback = buildBicepModelFeedback(ruleResult.feedback, true),
                        liveFeedback = buildBicepModelFeedback(ruleResult.feedback, true),
                        repStatus = BicepRepStatus.REP_GOOD,
                        repCompleted = true,
                        shouldCountRep = true
                    )
                }

                return ruleResult.copy(
                    isValid = false,
                    feedback = buildBicepModelFeedback(ruleResult.feedback, false),
                    liveFeedback = buildBicepModelFeedback(ruleResult.feedback, false),
                    repStatus = BicepRepStatus.REP_BAD,
                    repCompleted = true,
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

    private fun mergeLateralRaiseRuleAndClassifier(pose: PoseResult, ruleResult: RuleResult): RuleResult {
        val sequenceBuffer = lateralRaiseRepSequenceBuffer
        val classifier = lateralRaiseSequenceClassifier

        if (!pose.isValid()) {
            sequenceBuffer?.reset()
            return ruleResult
        }

        when (ruleResult.repStatus) {
            BicepRepStatus.IN_PROGRESS -> {
                sequenceBuffer?.append(pose.rawKeypoints)
                return ruleResult
            }
            BicepRepStatus.REP_BAD -> {
                sequenceBuffer?.append(pose.rawKeypoints)
                sequenceBuffer?.reset()
                val finalFeedback = buildLateralRaiseRulePriorityFeedback(ruleResult.feedback)
                return ruleResult.copy(
                    isValid = false,
                    feedback = finalFeedback,
                    liveFeedback = finalFeedback,
                    repStatus = BicepRepStatus.REP_BAD,
                    repCompleted = true,
                    shouldCountRep = false
                )
            }
            BicepRepStatus.REP_GOOD -> {
                sequenceBuffer?.append(pose.rawKeypoints)
                if (sequenceBuffer != null && classifier != null) {
                    val sequence = sequenceBuffer.buildResampledSequence(
                        targetLength = classifier.sequenceLength,
                        featureCount = classifier.featureCount
                    )
                    sequenceBuffer.reset()
                    if (sequence != null) {
                        runCatching { classifier.classify(sequence) }
                    }
                }

                val finalFeedback = buildLateralRaiseModelFeedback(
                    ruleFeedback = ruleResult.feedback,
                    predictedLabel = LateralRaiseSequenceClassifier.PredictedLabel.CORRECT
                )
                return ruleResult.copy(
                    isValid = true,
                    feedback = finalFeedback,
                    liveFeedback = finalFeedback,
                    repStatus = BicepRepStatus.REP_GOOD,
                    repCompleted = true,
                    shouldCountRep = true
                )
            }
            BicepRepStatus.IDLE -> {
                if (sequenceBuffer != null && !sequenceBuffer.isEmpty()) {
                    sequenceBuffer.reset()
                }
                return ruleResult
            }
        }
    }

    private fun mergeShoulderPressRuleAndClassifier(pose: PoseResult, ruleResult: RuleResult): RuleResult {
        val sequenceBuffer = shoulderPressRepSequenceBuffer ?: return ruleResult
        val classifier = shoulderPressSequenceClassifier ?: return ruleResult

        if (!pose.isValid()) {
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
                return ruleResult.copy(
                    isValid = false,
                    feedback = buildShoulderPressModelFeedback(ruleResult.feedback, false),
                    liveFeedback = buildShoulderPressModelFeedback(ruleResult.feedback, false),
                    repStatus = BicepRepStatus.REP_BAD,
                    repCompleted = true,
                    shouldCountRep = false
                )
            }
            BicepRepStatus.REP_GOOD -> {
                sequenceBuffer.append(pose.rawKeypoints)
                val sequence = sequenceBuffer.buildResampledSequence(
                    targetLength = classifier.sequenceLength,
                    featureCount = classifier.featureCount
                )
                sequenceBuffer.reset()
                if (sequence == null) {
                    return ruleResult
                }

                val classification = runCatching { classifier.classify(sequence) }.getOrNull() ?: return ruleResult
                return if (classification.isCorrect) {
                    ruleResult.copy(
                        isValid = true,
                        feedback = buildShoulderPressModelFeedback(ruleResult.feedback, true),
                        liveFeedback = buildShoulderPressModelFeedback(ruleResult.feedback, true),
                        repStatus = BicepRepStatus.REP_GOOD,
                        repCompleted = true,
                        shouldCountRep = true
                    )
                } else {
                    ruleResult.copy(
                        isValid = false,
                        feedback = buildShoulderPressModelFeedback(ruleResult.feedback, false),
                        liveFeedback = buildShoulderPressModelFeedback(ruleResult.feedback, false),
                        repStatus = BicepRepStatus.REP_BAD,
                        repCompleted = true,
                        shouldCountRep = false
                    )
                }
            }
            BicepRepStatus.IDLE -> {
                if (!sequenceBuffer.isEmpty()) {
                    sequenceBuffer.reset()
                }
                return ruleResult
            }
        }
    }

    private fun mergeSquatRuleAndClassifier(pose: PoseResult, ruleResult: RuleResult): RuleResult {
        val sequenceBuffer = squatRepSequenceBuffer ?: return ruleResult
        if (!ENABLE_SQUAT_CNN1D) {
            return mergeSquatRuleOnly(pose, ruleResult, sequenceBuffer)
        }
        val classifier = squatSequenceClassifier ?: run {
            updateCnnDebugIndicator("CNN1D TEST: model squat tidak tersedia")
            // Tetap cek side view meski classifier tidak ada
            val sv = getSquatSideViewMetrics(pose)
            return if (
                ruleResult.repStatus != BicepRepStatus.IDLE ||
                pose.isValid() && sv?.isAccepted == true
            ) {
                ruleResult
            } else {
                buildSquatSideViewIssue(ruleResult)
            }
        }

        val sideViewMetrics = getSquatSideViewMetrics(pose)
        val sideViewAccepted = pose.isValid() && sideViewMetrics?.isAccepted == true

        when (ruleResult.repStatus) {
            BicepRepStatus.IN_PROGRESS -> {
                if (!pose.isValid()) {
                    sequenceBuffer.reset()
                    updateCnnDebugIndicator("CNN1D TEST: menunggu pose valid")
                    return ruleResult
                }
                if (!sideViewAccepted) {
                    sequenceBuffer.reset()
                    val shoulderWidth = sideViewMetrics?.shoulderWidth?.let { String.format(Locale.US, "%.2f", it) } ?: "--"
                    val hipWidth = sideViewMetrics?.hipWidth?.let { String.format(Locale.US, "%.2f", it) } ?: "--"
                    val kneeWidth = sideViewMetrics?.kneeWidth?.let { String.format(Locale.US, "%.2f", it) } ?: "--"
                    val ankleWidth = sideViewMetrics?.ankleWidth?.let { String.format(Locale.US, "%.2f", it) } ?: "--"
                    val averageWidth = sideViewMetrics?.averageWidth?.let { String.format(Locale.US, "%.2f", it) } ?: "--"
                    val widthRatio = sideViewMetrics?.widthToHeightRatio?.let { String.format(Locale.US, "%.2f", it) } ?: "--"
                    updateCnnDebugIndicator(
                        "CNN1D TEST: OFF, wajib samping/serong (B:$shoulderWidth P:$hipWidth L:$kneeWidth K:$ankleWidth R:$averageWidth H:$widthRatio)"
                    )
                    return if (ruleResult.isValid) buildSquatSideViewIssue(ruleResult) else ruleResult
                }
                sequenceBuffer.append(pose.rawKeypoints)
                updateCnnDebugIndicator("CNN1D TEST: buffering sequence squat")
                return ruleResult
            }
            BicepRepStatus.REP_BAD -> {
                if (!sideViewAccepted) {
                    sequenceBuffer.reset()
                    updateCnnDebugIndicator("CNN1D TEST: rule squat SALAH, pakai feedback rule")
                    val clarifiedFeedback = buildSquatModelFeedback(ruleResult.feedback, false)
                    return ruleResult.copy(
                        isValid = false,
                        feedback = clarifiedFeedback,
                        liveFeedback = clarifiedFeedback,
                        repStatus = BicepRepStatus.REP_BAD,
                        repCompleted = true,
                        shouldCountRep = false,
                        isPositionIssue = false
                    )
                }
                if (pose.isValid()) {
                    sequenceBuffer.append(pose.rawKeypoints)
                }
                val sequence = sequenceBuffer.buildResampledSequence(
                    targetLength = classifier.sequenceLength,
                    featureCount = classifier.featureCount
                )
                sequenceBuffer.reset()
                if (sequence == null) {
                    updateCnnDebugIndicator("CNN1D TEST: rule squat salah, sequence belum cukup")
                    return ruleResult
                }

                val classification = runCatching { classifier.classify(sequence) }.getOrNull()
                if (classification == null) {
                    updateCnnDebugIndicator("CNN1D TEST: rule squat salah, inferensi gagal")
                    return ruleResult
                }

                val confidencePercent = (classification.confidence * 100f).roundToInt()
                val modelDecision = if (classification.isCorrect) "BENAR" else "SALAH"
                updateCnnDebugIndicator("CNN1D TEST: rule squat SALAH, model $modelDecision ${confidencePercent}%")
                return ruleResult.copy(
                    isValid = false,
                    feedback = buildSquatModelFeedback(ruleResult.feedback, false),
                    liveFeedback = buildSquatModelFeedback(ruleResult.feedback, false),
                    repStatus = BicepRepStatus.REP_BAD,
                    repCompleted = true,
                    shouldCountRep = false
                )
            }
            BicepRepStatus.REP_GOOD -> {
                if (!sideViewAccepted) {
                    sequenceBuffer.reset()
                    val shoulderWidth = sideViewMetrics?.shoulderWidth?.let { String.format(Locale.US, "%.2f", it) } ?: "--"
                    val hipWidth = sideViewMetrics?.hipWidth?.let { String.format(Locale.US, "%.2f", it) } ?: "--"
                    val kneeWidth = sideViewMetrics?.kneeWidth?.let { String.format(Locale.US, "%.2f", it) } ?: "--"
                    val ankleWidth = sideViewMetrics?.ankleWidth?.let { String.format(Locale.US, "%.2f", it) } ?: "--"
                    val averageWidth = sideViewMetrics?.averageWidth?.let { String.format(Locale.US, "%.2f", it) } ?: "--"
                    val widthRatio = sideViewMetrics?.widthToHeightRatio?.let { String.format(Locale.US, "%.2f", it) } ?: "--"
                    updateCnnDebugIndicator(
                        "CNN1D TEST: OFF, wajib samping/serong (B:$shoulderWidth P:$hipWidth L:$kneeWidth K:$ankleWidth R:$averageWidth H:$widthRatio)"
                    )
                    return buildSquatSideViewIssue(ruleResult)
                }

                if (pose.isValid() && sideViewAccepted) {
                    sequenceBuffer.append(pose.rawKeypoints)
                }
                val sequence = sequenceBuffer.buildResampledSequence(
                    targetLength = classifier.sequenceLength,
                    featureCount = classifier.featureCount
                )
                sequenceBuffer.reset()
                if (sequence == null) {
                    updateCnnDebugIndicator("CNN1D TEST: sequence squat belum cukup")
                    return ruleResult
                }

                val classification = runCatching { classifier.classify(sequence) }.getOrNull()
                if (classification == null) {
                    updateCnnDebugIndicator("CNN1D TEST: inferensi squat gagal")
                    return ruleResult
                }

                val confidencePercent = (classification.confidence * 100f).roundToInt()
                if (classification.isCorrect) {
                    updateCnnDebugIndicator("CNN1D TEST: jalan, BENAR ${confidencePercent}%")
                    return ruleResult.copy(
                        isValid = true,
                        feedback = buildSquatModelFeedback(ruleResult.feedback, true),
                        liveFeedback = buildSquatModelFeedback(ruleResult.feedback, true),
                        repStatus = BicepRepStatus.REP_GOOD,
                        repCompleted = true,
                        shouldCountRep = true
                    )
                }

                updateCnnDebugIndicator("CNN1D TEST: jalan, SALAH ${confidencePercent}%")
                val clarifiedFeedback = buildSquatModelFeedback(ruleResult.feedback, false)
                return ruleResult.copy(
                    isValid = false,
                    feedback = clarifiedFeedback,
                    liveFeedback = clarifiedFeedback,
                    repStatus = BicepRepStatus.REP_BAD,
                    shouldCountRep = false
                )
            }
            BicepRepStatus.IDLE -> {
                if (!sequenceBuffer.isEmpty()) {
                    sequenceBuffer.reset()
                }
                if (!pose.isValid()) {
                    updateCnnDebugIndicator("CNN1D TEST: menunggu pose valid")
                    return ruleResult
                }
                if (!sideViewAccepted) {
                    val shoulderWidth = sideViewMetrics?.shoulderWidth?.let { String.format(Locale.US, "%.2f", it) } ?: "--"
                    val hipWidth = sideViewMetrics?.hipWidth?.let { String.format(Locale.US, "%.2f", it) } ?: "--"
                    val kneeWidth = sideViewMetrics?.kneeWidth?.let { String.format(Locale.US, "%.2f", it) } ?: "--"
                    val ankleWidth = sideViewMetrics?.ankleWidth?.let { String.format(Locale.US, "%.2f", it) } ?: "--"
                    val averageWidth = sideViewMetrics?.averageWidth?.let { String.format(Locale.US, "%.2f", it) } ?: "--"
                    val widthRatio = sideViewMetrics?.widthToHeightRatio?.let { String.format(Locale.US, "%.2f", it) } ?: "--"
                    updateCnnDebugIndicator(
                        "CNN1D TEST: OFF, wajib samping/serong (B:$shoulderWidth P:$hipWidth L:$kneeWidth K:$ankleWidth R:$averageWidth H:$widthRatio)"
                    )
                    return buildSquatSideViewIssue(ruleResult)
                }
                val shoulderWidth = String.format(Locale.US, "%.2f", sideViewMetrics.shoulderWidth)
                val hipWidth = String.format(Locale.US, "%.2f", sideViewMetrics.hipWidth)
                val kneeWidth = String.format(Locale.US, "%.2f", sideViewMetrics.kneeWidth)
                val ankleWidth = String.format(Locale.US, "%.2f", sideViewMetrics.ankleWidth)
                val averageWidth = String.format(Locale.US, "%.2f", sideViewMetrics.averageWidth)
                val widthRatio = String.format(Locale.US, "%.2f", sideViewMetrics.widthToHeightRatio)
                updateCnnDebugIndicator("CNN1D TEST: samping/serong OK (B:$shoulderWidth P:$hipWidth L:$kneeWidth K:$ankleWidth R:$averageWidth H:$widthRatio)")
                return ruleResult
            }
        }
    }

    private fun mergeSquatRuleOnly(
        pose: PoseResult,
        ruleResult: RuleResult,
        sequenceBuffer: SquatRepSequenceBuffer
    ): RuleResult {
        val sideViewMetrics = getSquatSideViewMetrics(pose)
        val sideViewAccepted = pose.isValid() && sideViewMetrics?.isAccepted == true

        if (!sideViewAccepted) {
            return when (ruleResult.repStatus) {
                BicepRepStatus.IDLE -> {
                    if (!sequenceBuffer.isEmpty()) {
                        sequenceBuffer.reset()
                    }
                    val shoulderWidth = sideViewMetrics?.shoulderWidth?.let { String.format(Locale.US, "%.2f", it) } ?: "--"
                    updateCnnDebugIndicator("CNN1D TEST: squat nonaktif, wajib samping/serong (B:$shoulderWidth)")
                    buildSquatSideViewIssue(ruleResult)
                }
                BicepRepStatus.REP_BAD -> {
                    sequenceBuffer.reset()
                    updateCnnDebugIndicator("CNN1D TEST: squat nonaktif, rep SALAH dari rule")
                    ruleResult.copy(
                        isValid = false,
                        repStatus = BicepRepStatus.REP_BAD,
                        repCompleted = true,
                        shouldCountRep = false,
                        isPositionIssue = false
                    )
                }
                else -> ruleResult
            }
        }

        when (ruleResult.repStatus) {
            BicepRepStatus.IN_PROGRESS -> {
                if (pose.isValid()) {
                    sequenceBuffer.append(pose.rawKeypoints)
                }
                updateCnnDebugIndicator("CNN1D TEST: squat nonaktif, evaluasi rule-based")
            }
            BicepRepStatus.REP_GOOD -> {
                sequenceBuffer.reset()
                updateCnnDebugIndicator("CNN1D TEST: squat nonaktif, rep BENAR dari rule")
            }
            BicepRepStatus.REP_BAD -> {
                sequenceBuffer.reset()
                updateCnnDebugIndicator("CNN1D TEST: squat nonaktif, rep SALAH dari rule")
            }
            BicepRepStatus.IDLE -> {
                if (!sequenceBuffer.isEmpty()) {
                    sequenceBuffer.reset()
                }
                updateCnnDebugIndicator("CNN1D TEST: squat nonaktif, siap evaluasi")
            }
        }
        return ruleResult
    }

    private fun buildSquatSideViewIssue(ruleResult: RuleResult): RuleResult {
        return ruleResult.copy(
            isValid = false,
            feedback = "Harus menghadap ke samping serong",
            liveFeedback = "Harus menghadap ke samping serong",
            repStatus = BicepRepStatus.IDLE,
            repCompleted = false,
            shouldCountRep = false,
            isPositionIssue = true
        )
    }

    private fun buildSquatModelFeedback(ruleFeedback: String, isCorrect: Boolean): String {
        if (isCorrect) {
            return "Gerakan benar, kedalaman squat cukup, badan stabil, dan tempo terkontrol"
        }

        return when (ruleFeedback.trim()) {
            "Badan terlalu membungkuk, jaga badan tetap tegak" -> ruleFeedback
            "Tempo terlalu cepat, perlambat gerakan" -> ruleFeedback
            "Turunkan pinggul lebih dalam" -> ruleFeedback
            "Harus menghadap ke samping serong" -> ruleFeedback
            else -> "Ulangi squat dengan pinggul lebih stabil dan badan tetap tegak"
        }
    }

    private fun buildBicepModelFeedback(ruleFeedback: String, isCorrect: Boolean): String {
        if (isCorrect) {
            return "Gerakan benar, siku tetap stabil dan fleksi siku optimal"
        }

        return when (ruleFeedback.trim()) {
            "Tempo terlalu cepat, perlambat gerakan" -> ruleFeedback
            "Jaga siku tetap diam di samping tubuh" -> ruleFeedback
            "Angkat beban lebih tinggi hingga siku menekuk optimal" -> ruleFeedback
            "Jaga tubuh tetap tegak dan hindari ayunan badan" -> ruleFeedback
            "Lengan tidak terdeteksi" -> ruleFeedback
            "Harus menghadap ke samping" -> ruleFeedback
            "Gerakan benar, siku tetap stabil dan fleksi siku optimal" -> "Gerakan kurang tepat, ulangi dengan kontrol"
            "Fleksi siku optimal dan tubuh stabil" -> "Ulangi bicep curl dengan siku tetap diam dan gerakan terkontrol"
            else -> if (ruleFeedback.isNotBlank()) ruleFeedback else "Gerakan kurang tepat, ulangi dengan kontrol"
        }
    }

    private fun buildShoulderPressModelFeedback(ruleFeedback: String, isCorrect: Boolean): String {
        if (isCorrect) {
            return "Gerakan benar, dorongan lurus ke atas dan postur stabil"
        }

        return when (ruleFeedback.trim()) {
            "Dorong beban sampai hampir lurus dan jaga punggung tetap stabil" -> ruleFeedback
            "Jangan luruskan siku sepenuhnya" -> ruleFeedback
            "Jaga dorongan kedua lengan tetap simetris" -> ruleFeedback
            "Tempo terlalu cepat, perlambat gerakan" -> ruleFeedback
            "Hadapkan tubuh ke depan kamera" -> ruleFeedback
            "Pastikan kedua lengan terlihat jelas" -> ruleFeedback
            "Letakkan dumbel di atas bahu" -> ruleFeedback
            "Dorongan hampir lurus ke atas dan postur stabil" -> "Ulangi shoulder press dengan dorongan lurus dan kedua lengan seimbang"
            else -> if (ruleFeedback.isNotBlank()) ruleFeedback else "Gerakan shoulder press kurang tepat, ulangi dengan kontrol"
        }
    }

    private fun buildLateralRaiseModelFeedback(
        ruleFeedback: String,
        predictedLabel: LateralRaiseSequenceClassifier.PredictedLabel
    ): String {
        return when (predictedLabel) {
            LateralRaiseSequenceClassifier.PredictedLabel.CORRECT ->
                "Gerakan benar, tangan sejajar bahu dan tempo terkontrol"
            LateralRaiseSequenceClassifier.PredictedLabel.INCORRECT -> when (ruleFeedback.trim()) {
                "Tempo terlalu cepat, perlambat gerakan" -> ruleFeedback
                "Jaga siku tetap sedikit menekuk" -> ruleFeedback
                "Jangan angkat tangan lebih tinggi dari bahu" -> ruleFeedback
                "Angkat lengan sampai sejajar bahu" -> ruleFeedback
                "Angkat sedikit lagi sampai sejajar bahu" -> ruleFeedback
                "Pimpin gerakan dengan siku, jangan pergelangan tangan" -> ruleFeedback
                "Jangan angkat bahu saat mengangkat beban" -> ruleFeedback
                "Angkat beban ke samping serong, jangan terlalu ke pinggir" -> ruleFeedback
                "Jaga kedua lengan tetap seimbang" -> ruleFeedback
                "Jaga tubuh tetap tegak, jangan condong saat mengangkat" -> ruleFeedback
                "Angkat lengan setinggi bahu dan hindari tubuh condong" -> ruleFeedback
                "Lengan terangkat setinggi bahu dan tubuh stabil" -> "Ulangi lateral raise dengan tangan sejajar bahu dan tempo lebih terkontrol"
                else -> "Ulangi lateral raise dengan tangan sejajar bahu dan tempo lebih terkontrol"
            }
            LateralRaiseSequenceClassifier.PredictedLabel.UNKNOWN -> ruleFeedback
        }
    }

    private fun buildLateralRaiseRulePriorityFeedback(ruleFeedback: String): String {
        return when (ruleFeedback.trim()) {
            "Jangan angkat tangan lebih tinggi dari bahu" ->
                "Gerakan salah. Jangan angkat tangan lebih tinggi dari bahu"
            "Tempo terlalu cepat, perlambat gerakan" ->
                "Gerakan salah. Tempo terlalu cepat, perlambat gerakan"
            "Angkat lengan sampai sejajar bahu" ->
                "Gerakan salah. Angkat lengan sampai sejajar bahu"
            else -> "Gerakan salah. Ulangi lateral raise dengan tangan sejajar bahu dan tempo terkontrol"
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

    private fun isSquatSideView(pose: PoseResult): Boolean {
        return getSquatSideViewMetrics(pose)?.isAccepted == true
    }

    private fun getSquatSideViewMetrics(pose: PoseResult): SquatSideViewMetrics? {
        val keypoints = pose.rawKeypoints
        val leftShoulder = keypoints[Keypoint.LEFT_SHOULDER]
        val rightShoulder = keypoints[Keypoint.RIGHT_SHOULDER]
        if (
            leftShoulder.confidence <= SQUAT_BODY_SIDE_CONFIDENCE_MIN ||
            rightShoulder.confidence <= SQUAT_BODY_SIDE_CONFIDENCE_MIN
        ) {
            return null
        }

        val shoulderWidth = kotlin.math.abs(leftShoulder.x - rightShoulder.x)
        val nearSide = shoulderWidth <= SQUAT_SIDE_TO_DIAGONAL_SHOULDER_MAX

        return SquatSideViewMetrics(
            shoulderWidth = shoulderWidth,
            hipWidth = 0f,
            kneeWidth = 0f,
            ankleWidth = 0f,
            averageWidth = shoulderWidth,
            widthToHeightRatio = 0f,
            bodyHeight = 0f,
            widthSpread = 0f,
            isNearSide = nearSide,
            isFrontFacingRisk = !nearSide,
            isAccepted = nearSide
        )
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
                if (!legsVisible) "Harus menghadap ke samping serong" else "Posisi tubuh belum lengkap"
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
        // Cek posisi/orientasi dulu — langsung return tanpa masuk logika rep
        if (result.isPositionIssue) {
            return DetectionUiState(
                label = "POSISI",
                feedback = result.liveFeedback,
                isCorrect = false,
                isPositionIssue = true
            )
        }

        val now = SystemClock.elapsedRealtime()
        val squatMotionFallback =
            exerciseType == ExerciseType.SQUAT &&
                result.repStatus == BicepRepStatus.IDLE &&
                result.primaryMetric > 0f &&
                result.primaryMetric < 155f
        val lateralRaiseMotionFallback =
            exerciseType == ExerciseType.LATERAL_RAISE &&
                result.repStatus == BicepRepStatus.IDLE &&
                result.primaryMetric >= 20f &&
                result.primaryMetric < 110f

        // Tampilkan hasil rep selama periode display. Jangan timpa BENAR/SALAH
        // yang baru muncul oleh jitter frame berikutnya.
        if (now < repResultDisplayUntil) {
            return DetectionUiState(
                label = repResultLabel,
                feedback = repResultFeedback,
                isCorrect = repResultCorrect
            )
        }

        // Simpan hasil rep (BENAR/SALAH) untuk ditampilkan selama REP_RESULT_DISPLAY_MS
        if (result.repStatus == BicepRepStatus.REP_GOOD || result.repStatus == BicepRepStatus.REP_BAD) {
            repResultDisplayUntil = now + REP_RESULT_DISPLAY_MS
            repResultLabel = if (result.repStatus == BicepRepStatus.REP_GOOD) "BENAR" else "SALAH"
            repResultFeedback = result.feedback
            repResultCorrect = result.repStatus == BicepRepStatus.REP_GOOD
        }

        // SQUAT: saat gerakan berlangsung, tampilkan coaching netral
        if (exerciseType == ExerciseType.SQUAT && !result.repCompleted) {
            return when {
                result.repStatus == BicepRepStatus.IN_PROGRESS || squatMotionFallback -> DetectionUiState(
                    label = "GERAKAN",
                    feedback = getInProgressCoaching(),
                    isCorrect = true
                )
                else -> DetectionUiState(
                    label = "SIAP",
                    feedback = result.liveFeedback,
                    isCorrect = true
                )
            }
        }

        // LATERAL RAISE: saat gerakan berlangsung, tampilkan coaching netral
        if (exerciseType == ExerciseType.LATERAL_RAISE && !result.repCompleted) {
            return when {
                result.repStatus == BicepRepStatus.IN_PROGRESS || lateralRaiseMotionFallback -> DetectionUiState(
                    label = "GERAKAN",
                    feedback = getInProgressCoaching(),
                    isCorrect = true
                )
                else -> DetectionUiState(
                    label = "SIAP",
                    feedback = result.liveFeedback,
                    isCorrect = true
                )
            }
        }

        if (squatMotionFallback) {
            return DetectionUiState(
                label = "GERAKAN",
                feedback = getInProgressCoaching(),
                isCorrect = true
            )
        }

        return when (result.repStatus) {
            BicepRepStatus.IN_PROGRESS -> DetectionUiState(
                label = "GERAKAN",
                feedback = if (result.liveFeedback == "Tahan siku sejajar bahu sebentar") {
                    result.liveFeedback
                } else {
                    getInProgressCoaching()
                },
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

    /**
     * Teks coaching netral yang ditampilkan saat gerakan sedang berlangsung (IN_PROGRESS).
     * Tidak mengandung evaluasi form — hanya panduan tempo/kontrol.
     */
    private fun getInProgressCoaching(): String = when (exerciseType) {
        ExerciseType.BICEP_CURL    -> "Lakukan curl dengan tempo terkontrol"
        ExerciseType.LATERAL_RAISE -> "Angkat lengan ke samping dengan perlahan"
        ExerciseType.SHOULDER_PRESS -> "Dorong beban ke atas dengan stabil"
        ExerciseType.SQUAT         -> "Turunkan pinggul dengan kontrol"
    }

    private fun resetRepResultState() {
        repResultDisplayUntil = 0L
        repResultLabel = "SIAP"
        repResultFeedback = "Siap untuk repetisi berikutnya"
        repResultCorrect = true
    }

    private fun updateUi(label: String, confidence: Float, feedback: String, isCorrect: Boolean, pose: PoseResult?, isPositionIssue: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastUiUpdateTime < LIVE_UI_UPDATE_INTERVAL_MS) return
        lastUiUpdateTime = now

        // Simpan state untuk overlay rekaman video
        recordingLabel = label
        recordingFeedback = feedback
        recordingIsCorrect = isCorrect

        requireActivity().runOnUiThread {
            val b = _binding ?: return@runOnUiThread
            b.tvLabel.text = label
            // Warna label: hijau=BENAR, oranye=POSISI, merah=SALAH
            b.tvLabel.setTextColor(
                when {
                    isCorrect -> Color.parseColor("#22C55E")      // hijau
                    isPositionIssue -> Color.parseColor("#F97316") // oranye
                    else -> Color.parseColor("#EF4444")           // merah
                }
            )

            val pct = (confidence * 100).toInt()
            b.tvConfidence.text = "$pct%"
            b.progressConfidence.progress = pct

            b.tvReps.text = repCounter.getRepCount().toString()
            b.tvFeedback.text = feedback
            b.tvCnnIndicator.text = cnnDebugIndicatorText
            b.overlayView.updatePose(pose, isCorrect)
        }
    }

    private fun updateCnnDebugIndicator(message: String) {
        cnnDebugIndicatorText = message
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
            append("Atur jarak sekitar dua setengah sampai tiga meter, lalu sesuaikan sampai kepala dan kaki tetap masuk kamera. ")
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

        // Setelah 1 rep selesai: ucapkan feedback lengkap dan spesifik (BENAR atau SALAH)
        if (usesRepCompletionFlow() && result.repCompleted) {
            val speech = if (result.shouldCountRep) {
                // BENAR: ucapkan feedback deskriptif penuh
                result.feedback
            } else {
                // SALAH: tambahkan prefix "Gerakan salah" jika belum ada
                when {
                    result.feedback.startsWith("Gerakan") -> result.feedback
                    result.feedback.startsWith("Ulangi") -> result.feedback
                    result.feedback.isBlank() -> "Gerakan salah"
                    else -> "Gerakan salah. ${result.feedback}"
                }
            }
            realtimeTtsManager?.speakIfEligible(
                message = speech,
                utteranceKey = "rep:${result.shouldCountRep}:${normalizeSpeechKey(result.feedback)}",
                cooldownMs = 1200L,
                minIntervalMs = 650L
            )
            return
        }

        // Saat gerakan berlangsung (IN_PROGRESS): tidak ada TTS evaluatif
        if (usesRepCompletionFlow() && result.repStatus == BicepRepStatus.IN_PROGRESS) return

        // Kondisi HILANG / pose tidak valid: tetap berikan panduan posisi
        val speech = when {
            uiState.label == "HILANG" -> mapFeedbackToSpeech(uiState.feedback)
            uiState.isPositionIssue   -> mapFeedbackToSpeech(uiState.feedback)
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
            "Harus menghadap ke samping serong" -> "Harus menghadap ke samping serong"
            "Hadapkan tubuh ke depan kamera" -> "Hadapkan tubuh ke depan kamera"
            "Tempo terlalu cepat, perlambat gerakan" -> "Tempo terlalu cepat, perlambat gerakan"
            "Jaga siku tetap diam di samping tubuh" -> "Jaga siku tetap diam di samping tubuh"
            "Angkat beban lebih tinggi hingga siku menekuk optimal" -> "Angkat beban lebih tinggi hingga siku menekuk optimal"
            "Jaga tubuh tetap tegak dan hindari ayunan badan" -> "Jaga tubuh tetap tegak dan hindari ayunan badan"
            "Jaga tubuh tetap tegak, jangan terlalu bergoyang" -> "Jaga tubuh tetap tegak, jangan terlalu bergoyang"
            "Angkat beban lebih tinggi dan jaga tubuh tetap tegak" -> "Angkat beban lebih tinggi dan jaga tubuh tetap tegak"
            "Gerakan kurang tepat, ulangi dengan kontrol" -> "Gerakan kurang tepat, ulangi dengan kontrol"
            "Jaga dorongan kedua lengan tetap simetris" -> "Jaga dorongan kedua lengan tetap simetris"
            "Dorong beban lurus ke atas sampai tangan hampir lurus" -> "Dorong beban lurus ke atas sampai tangan hampir lurus"
            "Dorong beban sampai hampir lurus dan jaga punggung tetap stabil" -> "Dorong beban sampai hampir lurus dan jaga punggung tetap stabil"
            "Jangan luruskan siku sepenuhnya" -> "Jangan luruskan siku sepenuhnya"
            "Pastikan kedua lengan terlihat jelas" -> "Pastikan kedua lengan terlihat jelas"
            "Letakkan dumbel di atas bahu" -> "Letakkan dumbel di atas bahu"
            "Angkat beban sedikit lebih tinggi" -> "Angkat beban sedikit lebih tinggi"
            "Jaga siku tetap sedikit menekuk" -> "Tangan harus sedikit menekuk"
            "Angkat lengan sampai sejajar bahu" -> "Angkat lengan sampai sejajar bahu"
            "Angkat sedikit lagi sampai sejajar bahu" -> "Angkat sedikit lagi sampai sejajar bahu"
            "Angkat siku sejajar bahu, jangan lebih tinggi dari bahu" -> "Angkat siku sejajar bahu, jangan lebih tinggi dari bahu"
            "Jangan angkat tangan lebih tinggi dari bahu" -> "Jangan angkat tangan lebih tinggi dari bahu"
            "Angkat lengan setinggi bahu dan hindari tubuh condong" -> "Angkat lengan setinggi bahu dan hindari tubuh condong"
            "Jaga tubuh tetap tegak, jangan condong saat mengangkat" -> "Jaga tubuh tetap tegak, jangan condong saat mengangkat"
            "Pimpin gerakan dengan siku, jangan pergelangan tangan" -> "Pimpin gerakan dengan siku, jangan pergelangan tangan"
            "Jangan angkat bahu saat mengangkat beban" -> "Jangan angkat bahu saat mengangkat beban"
            "Jaga tubuh tetap stabil, jangan bergoyang" -> "Jaga tubuh tetap stabil, jangan bergoyang"
            "Angkat beban ke samping serong, jangan terlalu ke pinggir" -> "Angkat beban ke samping serong, jangan terlalu ke pinggir"
            "Jaga kedua lengan tetap seimbang" -> "Jaga kedua lengan tetap seimbang"
            "Postur gerakan salah, jaga tangan sejajar bahu dan jangan lebih tinggi dari bahu" -> "Postur gerakan salah, jaga tangan sejajar bahu dan jangan lebih tinggi dari bahu"
            "Gerakan benar, siku tetap stabil dan fleksi siku optimal" -> "Gerakan benar"
            "Gerakan benar, kedalaman squat cukup dan badan tetap stabil" -> "Gerakan benar"
            "Gerakan benar, kedalaman squat cukup, badan stabil, dan tempo terkontrol" -> "Gerakan benar"
            "Gerakan benar, dorongan lurus ke atas dan postur stabil" -> "Gerakan benar"
            "Gerakan benar, tangan sejajar bahu dan tempo terkontrol" -> "Gerakan benar"
            "Ulangi lateral raise dengan tangan sejajar bahu dan tubuh tetap stabil" -> "Ulangi lateral raise dengan tangan sejajar bahu dan tubuh tetap stabil"
            "Ulangi lateral raise dengan tangan sejajar bahu dan tempo lebih terkontrol" -> "Ulangi lateral raise dengan tangan sejajar bahu dan tempo lebih terkontrol"
            "Ulangi bicep curl dengan siku tetap diam dan gerakan terkontrol" -> "Ulangi bicep curl dengan siku tetap diam dan gerakan terkontrol"
            "Ulangi shoulder press dengan dorongan lurus dan kedua lengan seimbang" -> "Ulangi shoulder press dengan dorongan lurus dan kedua lengan seimbang"
            "Ulangi squat dengan pinggul lebih stabil dan badan tetap tegak" -> "Ulangi squat dengan pinggul lebih stabil dan badan tetap tegak"
            "Kaki tidak terdeteksi" -> "Kaki tidak terdeteksi"
            "Turunkan pinggul lebih dalam dan jaga tubuh tetap stabil" -> "Turunkan pinggul lebih dalam dan jaga tubuh tetap stabil"
            "Turunkan pinggul lebih dalam" -> "Turunkan pinggul lebih dalam"
            "Jaga badan tetap tegak dan stabil" -> "Jaga badan tetap tegak dan stabil"
            "Badan terlalu membungkuk, jaga badan tetap tegak" -> "Badan terlalu membungkuk, jaga badan tetap tegak"
            "Posisi tubuh belum lengkap" -> "Posisi tubuh belum lengkap"
            "Pastikan tubuh terlihat jelas di kamera" -> "Pastikan tubuh terlihat jelas di kamera"
            else -> null
        }
    }

    private fun mapLiveCoachingToSpeech(feedback: String): String? {
        return when (feedback.trim()) {
            "Tempo terlalu cepat, perlambat gerakan" -> feedback
            "Jaga siku tetap diam di samping tubuh" -> feedback
            "Angkat beban lebih tinggi hingga siku menekuk optimal" -> feedback
            "Jaga tubuh tetap tegak dan hindari ayunan badan" -> feedback
            "Jaga tubuh tetap tegak, jangan terlalu bergoyang" -> feedback
            "Angkat beban lebih tinggi dan jaga tubuh tetap tegak" -> feedback
            "Jaga dorongan kedua lengan tetap simetris" -> feedback
            "Dorong beban lurus ke atas sampai tangan hampir lurus" -> feedback
            "Dorong beban sampai hampir lurus dan jaga punggung tetap stabil" -> feedback
            "Jangan luruskan siku sepenuhnya" -> feedback
            "Letakkan dumbel di atas bahu" -> feedback
            "Angkat beban sedikit lebih tinggi" -> feedback
            "Jaga siku tetap sedikit menekuk" -> feedback
            "Angkat lengan sampai sejajar bahu" -> feedback
            "Angkat sedikit lagi sampai sejajar bahu" -> feedback
            "Angkat siku sejajar bahu, jangan lebih tinggi dari bahu" -> feedback
            "Jangan angkat tangan lebih tinggi dari bahu" -> feedback
            "Angkat lengan setinggi bahu dan hindari tubuh condong" -> feedback
            "Jaga tubuh tetap tegak, jangan condong saat mengangkat" -> feedback
            "Pimpin gerakan dengan siku, jangan pergelangan tangan" -> feedback
            "Jangan angkat bahu saat mengangkat beban" -> feedback
            "Jaga tubuh tetap stabil, jangan bergoyang" -> feedback
            "Angkat beban ke samping serong, jangan terlalu ke pinggir" -> feedback
            "Jaga kedua lengan tetap seimbang" -> feedback
            "Turunkan pinggul lebih dalam dan jaga tubuh tetap stabil" -> feedback
            "Turunkan pinggul lebih dalam" -> feedback
            "Jaga badan tetap tegak dan stabil" -> feedback
            "Badan terlalu membungkuk, jaga badan tetap tegak" -> feedback
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
        val isCorrect: Boolean,
        val isPositionIssue: Boolean = false  // true → label "POSISI" oranye
    )

    private data class SquatSideViewMetrics(
        val shoulderWidth: Float,
        val hipWidth: Float,
        val kneeWidth: Float,
        val ankleWidth: Float,
        val averageWidth: Float,
        val widthToHeightRatio: Float,
        val bodyHeight: Float,
        val widthSpread: Float,
        val isNearSide: Boolean,
        val isFrontFacingRisk: Boolean,
        val isAccepted: Boolean
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
        val correctReps = repCounter.getRepCount()
        if (feedbackMap.isEmpty()) {
            return if (correctReps > 0) {
                "Evaluasi sesi:\n- ${buildGoodSessionFeedback()} (${correctReps}x, 100%)"
            } else {
                "Evaluasi sesi:\n- Tidak ada data feedback untuk sesi ini."
            }
        }

        val totalIssues = feedbackMap.values.sum().coerceAtLeast(1)
        val totalObservations = (totalIssues + correctReps).coerceAtLeast(1)
        val sortedFeedback = feedbackMap.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })

        val summaryLines = mutableListOf<String>()
        if (correctReps > 0) {
            val correctPercentage = ((correctReps.toFloat() / totalObservations.toFloat()) * 100f).roundToInt()
            summaryLines += "- ${buildGoodSessionFeedback()} (${correctReps}x, ${correctPercentage}%)"
        }

        summaryLines += sortedFeedback.take(3).map { entry ->
            val percentage = ((entry.value.toFloat() / totalObservations.toFloat()) * 100f).roundToInt()
            "- ${entry.key} (${entry.value}x, ${percentage}%)"
        }

        val shownIssueCount = sortedFeedback.take(3).size
        val remainingCount = sortedFeedback.size - shownIssueCount
        if (remainingCount > 0) {
            summaryLines += "- ${remainingCount} feedback lain juga muncul selama sesi"
        }

        return buildString {
            append("Evaluasi sesi:")
            append('\n')
            append(summaryLines.joinToString("\n"))
        }
    }

    private fun buildGoodSessionFeedback(): String {
        return when (exerciseType) {
            ExerciseType.BICEP_CURL -> "Gerakan benar, siku tetap stabil dan fleksi siku optimal"
            ExerciseType.SQUAT -> "Gerakan benar, kedalaman squat cukup, badan stabil, dan tempo terkontrol"
            ExerciseType.LATERAL_RAISE -> "Gerakan benar, tangan sejajar bahu dan tempo terkontrol"
            ExerciseType.SHOULDER_PRESS -> "Gerakan benar, dorongan lurus ke atas dan postur stabil"
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
        recordingSourceRect = null
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
        runCatching { lateralRaiseSequenceClassifier?.close() }
        runCatching { shoulderPressSequenceClassifier?.close() }
        runCatching { squatSequenceClassifier?.close() }
        bicepSequenceClassifier = null
        bicepRepSequenceBuffer = null
        lateralRaiseSequenceClassifier = null
        lateralRaiseRepSequenceBuffer = null
        shoulderPressSequenceClassifier = null
        shoulderPressRepSequenceBuffer = null
        squatSequenceClassifier = null
        squatRepSequenceBuffer = null
        recordingBitmap?.recycle()
        recordingBitmap = null
        recordingSourceRect = null
        recordingDrawExecutor.shutdownNow()
        _binding = null
    }

    override fun onResume() {
        super.onResume()
        if (!ENABLE_RULES_OVERLAY) return
        if (!isDetecting || detectionState != DetectionState.RULES_OVERLAY || hasSpokenRulesGuidance) return
        _binding?.layoutRulesOverlay?.visibility = View.VISIBLE
        _binding?.tvRulesReadyHint?.text = "Panduan evaluasi sedang dibacakan, tunggu sebentar"
        speakRulesGuidanceIfNeeded()
    }

    override fun onStop() {
        super.onStop()
        if (!ENABLE_RULES_OVERLAY) {
            realtimeTtsManager?.stop()
            return
        }
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

        val externalDir = requireContext().getExternalFilesDir("evaluations")
        val baseDir = externalDir ?: File(requireContext().filesDir, "evaluations")
        baseDir.mkdirs()
        val previewW = _binding?.previewView?.width?.takeIf { it > 0 } ?: 720
        val previewH = _binding?.previewView?.height?.takeIf { it > 0 } ?: 1280
        val initialPreviewBitmap = _binding?.previewView?.bitmap
        val sourceRect = initialPreviewBitmap?.let { bitmap ->
            detectActivePreviewRect(bitmap)
        }
        recordingSourceRect = sourceRect
        initialPreviewBitmap?.recycle()
        val recordingSourceW = sourceRect?.width()?.takeIf { it > 0 } ?: previewW
        val recordingSourceH = sourceRect?.height()?.takeIf { it > 0 } ?: previewH
        val recorder = EvaluationVideoRecorder(
            baseDir = baseDir,
            targetFps = EVALUATION_RECORDING_FPS,
            maxDimension = EVALUATION_RECORDING_MAX_DIMENSION
        )
        val recordedPath = recorder.startRecording(
            exerciseType = exerciseType.name,
            sourceWidth = recordingSourceW,
            sourceHeight = recordingSourceH
        )

        if (recordedPath.isNullOrBlank()) {
            hasEvaluationRecordingFailed = true
            evaluationVideoPath = null
            return
        }

        hasRequestedEvaluationRecordingStart = true
        evaluationVideoRecorder = recorder
        evaluationVideoPath = recordedPath
        startEvaluationFrameCapture()
    }

    private suspend fun stopEvaluationRecordingAndFinalize(): String? {
        stopEvaluationFrameCapture()
        val recorder = evaluationVideoRecorder
        evaluationVideoRecorder = null
        recordingSourceRect = null

        val finalizedPath = if (recorder != null && recorder.isActive()) {
            kotlinx.coroutines.withContext(Dispatchers.IO) {
                recorder.stopAndFinalize()
            }
        } else if (::cameraManager.isInitialized && cameraManager.isEvaluationRecording()) {
            suspendCancellableCoroutine<String?> { continuation ->
                cameraManager.stopEvaluationRecording { path ->
                    if (continuation.isActive) continuation.resume(path)
                }
            }
        } else {
            evaluationVideoPath?.takeIf { File(it).exists() }
        }

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
        val sourceRect = recordingSourceRect
            ?.takeIf { it.width() > 0 && it.height() > 0 && it.right <= previewBitmap.width && it.bottom <= previewBitmap.height }
            ?: Rect(0, 0, previewBitmap.width, previewBitmap.height)
        val (captureWidth, captureHeight) = computeRecordingCaptureSize(
            sourceRect.width(),
            sourceRect.height()
        )

        val composite = getOrCreateRecordingBitmap(captureWidth, captureHeight) ?: return
        val poseSnapshot = lastEvaluationDisplayPose
        val labelSnapshot = recordingLabel
        val feedbackSnapshot = recordingFeedback
        val isCorrectSnapshot = recordingIsCorrect
        val repSnapshot = repCounter.getRepCount()
        val isFront = cameraManager.isFrontCamera()

        recordingDrawExecutor.execute {
            drawCompositeAndEncode(
                composite,
                previewBitmap,
                sourceRect,
                poseSnapshot,
                labelSnapshot,
                feedbackSnapshot,
                isCorrectSnapshot,
                repSnapshot,
                isFront,
                recorder
            )
            previewBitmap.recycle()
        }
    }

    /** Gambar semua layer ke [composite] lalu kirim ke recorder. Dipanggil dari background thread. */
    private fun drawCompositeAndEncode(
        composite: Bitmap,
        previewBitmap: Bitmap,
        sourceRect: Rect,
        pose: PoseResult?,
        label: String,
        feedback: String,
        isCorrect: Boolean,
        reps: Int,
        isFront: Boolean,
        recorder: EvaluationVideoRecorder
    ) {
        if (!recorder.isActive()) return
        val w = composite.width
        val h = composite.height
        val canvas = Canvas(composite)
        canvas.drawColor(Color.BLACK)

        // Preview kamera murni dari PreviewView, lalu overlay evaluasi digambar manual.
        canvas.drawBitmap(previewBitmap, sourceRect, android.graphics.Rect(0, 0, w, h), null)

        if (pose != null) {
            drawSkeletonOnCanvas(
                canvas = canvas,
                pose = pose,
                w = w.toFloat(),
                h = h.toFloat(),
                isFront = isFront,
                previewWidth = previewBitmap.width,
                previewHeight = previewBitmap.height,
                sourceRect = sourceRect
            )
        }

        drawHudOnCanvas(canvas, w, h, label, feedback, isCorrect, reps)

        val encodeBitmap = composite.copy(Bitmap.Config.ARGB_8888, false)
        recorder.recordFrame(encodeBitmap, 0L)
    }

    /** Reuse pre-allocated bitmap jika dimensi sama, buat baru jika berubah. */
    private fun getOrCreateRecordingBitmap(w: Int, h: Int): Bitmap? {
        val existing = recordingBitmap
        if (existing != null && !existing.isRecycled && existing.width == w && existing.height == h) {
            return existing
        }
        existing?.recycle()
        val bmp = Bitmap.createBitmap(w.coerceAtLeast(1), h.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        recordingBitmap = bmp
        return bmp
    }

    private fun computeRecordingCaptureSize(width: Int, height: Int): Pair<Int, Int> {
        if (width <= 0 || height <= 0) {
            return EVALUATION_RECORDING_MAX_DIMENSION to EVALUATION_RECORDING_MAX_DIMENSION
        }

        val maxDimension = maxOf(width, height)
        val scale = if (maxDimension > EVALUATION_RECORDING_MAX_DIMENSION) {
            EVALUATION_RECORDING_MAX_DIMENSION.toFloat() / maxDimension.toFloat()
        } else {
            1f
        }

        val scaledWidth = ((width * scale).roundToInt() / 2 * 2).coerceAtLeast(2)
        val scaledHeight = ((height * scale).roundToInt() / 2 * 2).coerceAtLeast(2)
        return scaledWidth to scaledHeight
    }

    private fun detectActivePreviewRect(bitmap: Bitmap): Rect {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) return Rect(0, 0, 1, 1)

        val step = maxOf(4, minOf(width, height) / 120)
        val minContentHits = 2

        fun isContentPixel(x: Int, y: Int): Boolean {
            val color = bitmap.getPixel(x.coerceIn(0, width - 1), y.coerceIn(0, height - 1))
            val alpha = Color.alpha(color)
            val brightness = Color.red(color) + Color.green(color) + Color.blue(color)
            return alpha > 8 && brightness > 24
        }

        fun rowHasContent(y: Int): Boolean {
            var hits = 0
            var x = 0
            while (x < width) {
                if (isContentPixel(x, y)) {
                    hits++
                    if (hits >= minContentHits) return true
                }
                x += step
            }
            return false
        }

        fun columnHasContent(x: Int): Boolean {
            var hits = 0
            var y = 0
            while (y < height) {
                if (isContentPixel(x, y)) {
                    hits++
                    if (hits >= minContentHits) return true
                }
                y += step
            }
            return false
        }

        var top = 0
        while (top < height && !rowHasContent(top)) top += step

        var bottom = height - 1
        while (bottom > top && !rowHasContent(bottom)) bottom -= step

        var left = 0
        while (left < width && !columnHasContent(left)) left += step

        var right = width - 1
        while (right > left && !columnHasContent(right)) right -= step

        if (right <= left || bottom <= top) return Rect(0, 0, width, height)

        val margin = maxOf(step * 2, (minOf(width, height) * 0.02f).roundToInt())
        val rect = Rect(
            (left - margin).coerceAtLeast(0),
            (top - margin).coerceAtLeast(0),
            (right + margin + 1).coerceAtMost(width),
            (bottom + margin + 1).coerceAtMost(height)
        )

        val croppedArea = rect.width().toFloat() * rect.height().toFloat()
        val fullArea = width.toFloat() * height.toFloat()
        return if (croppedArea < fullArea * 0.15f) {
            Rect(0, 0, width, height)
        } else {
            rect
        }
    }

    /**
     * Gambar skeleton (bones + keypoints) langsung ke canvas.
     * Dipanggil dari background thread — tidak boleh akses UI atau volatile fields.
     */
    private fun drawSkeletonOnCanvas(
        canvas: Canvas,
        pose: PoseResult,
        w: Float,
        h: Float,
        isFront: Boolean,
        previewWidth: Int,
        previewHeight: Int,
        sourceRect: Rect
    ) {
        val isCorrect = recordingIsCorrect
        val boneColor = if (isCorrect) Color.parseColor("#AA66B38C") else Color.parseColor("#AAD39A62")
        val pointColor = Color.parseColor("#99D9E4EC")
        val pointInnerColor = Color.parseColor("#AAFFFFFF")

        val sourceWidth = pose.sourceWidth.takeIf { it > 0f } ?: w
        val sourceHeight = pose.sourceHeight.takeIf { it > 0f } ?: h
        val sourceAspect = sourceWidth / sourceHeight
        val previewW = previewWidth.takeIf { it > 0 }?.toFloat() ?: w
        val previewH = previewHeight.takeIf { it > 0 }?.toFloat() ?: h
        val previewAspect = previewW / previewH

        val previewBounds = if (sourceAspect > previewAspect) {
            val contentWidth = previewH * sourceAspect
            val horizontalOffset = (previewW - contentWidth) / 2f
            android.graphics.RectF(horizontalOffset, 0f, horizontalOffset + contentWidth, previewH)
        } else {
            val contentHeight = previewW / sourceAspect
            val verticalOffset = (previewH - contentHeight) / 2f
            android.graphics.RectF(0f, verticalOffset, previewW, verticalOffset + contentHeight)
        }

        fun mapX(nx: Float): Float {
            val x = nx.coerceIn(0f, 1f)
            val previewX = if (isFront) {
                previewBounds.left + (1f - x) * previewBounds.width()
            } else {
                previewBounds.left + x * previewBounds.width()
            }
            return ((previewX - sourceRect.left) / sourceRect.width().coerceAtLeast(1)) * w
        }

        fun mapY(ny: Float): Float {
            val previewY = previewBounds.top + ny.coerceIn(0f, 1f) * previewBounds.height()
            return ((previewY - sourceRect.top) / sourceRect.height().coerceAtLeast(1)) * h
        }

        val bonePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 5f
            strokeCap = android.graphics.Paint.Cap.ROUND
            color = boneColor
        }
        val kpPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.FILL
        }

        // Gambar tulang (bone connections)
        for ((startIdx, endIdx) in com.modul.gymai.pose.Keypoint.SKELETON_CONNECTIONS) {
            val s = pose.keypoints.getOrNull(startIdx) ?: continue
            val e = pose.keypoints.getOrNull(endIdx) ?: continue
            if (s.confidence < 0.4f || e.confidence < 0.4f) continue
            canvas.drawLine(mapX(s.x), mapY(s.y), mapX(e.x), mapY(e.y), bonePaint)
        }

        // Gambar titik keypoint
        for (kp in pose.keypoints) {
            if (kp.confidence < 0.4f) continue
            val cx = mapX(kp.x)
            val cy = mapY(kp.y)
            kpPaint.color = pointColor
            canvas.drawCircle(cx, cy, 6.5f, kpPaint)
            kpPaint.color = pointInnerColor
            canvas.drawCircle(cx, cy, 6.5f * 0.34f, kpPaint)
        }
    }

    /**
     * Gambar HUD bar di bagian bawah frame.
     * Dipanggil dari background thread — semua state diterima sebagai parameter.
     */
    private fun drawHudOnCanvas(
        canvas: Canvas, frameWidth: Int, frameHeight: Int,
        label: String, feedback: String, isCorrect: Boolean, reps: Int
    ) {
        val hudMaxHeight = maxOf(36, minOf(72, frameHeight / 3))
        val hudHeight = (frameHeight * 0.13f).roundToInt().coerceIn(36, hudMaxHeight)
        val hudTop = frameHeight - hudHeight
        val horizontalPadding = maxOf(8f, frameWidth * 0.026f)
        val gap = maxOf(6f, frameWidth * 0.018f)
        val centerY = hudTop + hudHeight / 2f

        // Background HUD semi-transparan
        val bgPaint = android.graphics.Paint().apply {
            color = Color.parseColor("#B3000000")
        }
        canvas.drawRect(
            0f, hudTop.toFloat(), frameWidth.toFloat(), frameHeight.toFloat(), bgPaint
        )

        val labelColor = when {
            isCorrect -> Color.parseColor("#22C55E")       // hijau: BENAR
            label == "POSISI" -> Color.parseColor("#F97316") // oranye: posisi salah
            else -> Color.parseColor("#EF4444")            // merah: SALAH
        }
        val chipBg = when {
            isCorrect -> Color.parseColor("#3322C55E")
            label == "POSISI" -> Color.parseColor("#33F97316")
            else -> Color.parseColor("#33EF4444")
        }

        // Chip label (kiri)
        val chipPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = chipBg
        }
        val labelTextPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = labelColor
            textSize = (hudHeight * 0.31f).coerceIn(11f, 20f)
            textAlign = android.graphics.Paint.Align.CENTER
            isFakeBoldText = true
        }
        val labelForChip = fitText(label, labelTextPaint, frameWidth * 0.34f)
        val chipHeight = (hudHeight * 0.56f).coerceIn(22f, 34f)
        val chipLeft = horizontalPadding
        val chipTop = centerY - chipHeight / 2f
        val chipRight = chipLeft + labelTextPaint.measureText(labelForChip) + horizontalPadding * 1.6f
        val chipBottom = chipTop + chipHeight
        canvas.drawRoundRect(
            android.graphics.RectF(chipLeft, chipTop, chipRight, chipBottom),
            chipHeight * 0.22f, chipHeight * 0.22f, chipPaint
        )
        canvas.drawText(
            labelForChip,
            (chipLeft + chipRight) / 2f,
            baselineForCenteredText(labelTextPaint, chipTop, chipBottom),
            labelTextPaint
        )

        val repText = "$reps Rep"
        val repReservedWidth = maxOf(54f, frameWidth * 0.18f)

        // Teks feedback (tengah)
        val feedbackPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = (hudHeight * 0.25f).coerceIn(10f, 16f)
            textAlign = android.graphics.Paint.Align.LEFT
            alpha = 220
        }
        val feedbackX = chipRight + gap
        val feedbackMaxWidth = frameWidth - feedbackX - horizontalPadding - repReservedWidth - gap
        val displayFeedback = if (feedback.isBlank() || feedbackMaxWidth <= frameWidth * 0.14f) {
            ""
        } else if (
            feedbackPaint.measureText(feedback) > feedbackMaxWidth) {
            // Truncate jika terlalu panjang
            var truncated = feedback
            while (truncated.isNotEmpty() && feedbackPaint.measureText("$truncated...") > feedbackMaxWidth) {
                truncated = truncated.dropLast(1)
            }
            "$truncated..."
        } else feedback
        if (displayFeedback.isNotBlank()) {
            canvas.drawText(
                displayFeedback,
                feedbackX,
                baselineForCenteredText(feedbackPaint, hudTop.toFloat(), frameHeight.toFloat()),
                feedbackPaint
            )
        }

        // Rep count (kanan)
        val repPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = (hudHeight * 0.33f).coerceIn(12f, 22f)
            textAlign = android.graphics.Paint.Align.RIGHT
            isFakeBoldText = true
        }
        canvas.drawText(
            repText,
            frameWidth - horizontalPadding,
            baselineForCenteredText(repPaint, hudTop.toFloat(), frameHeight.toFloat()),
            repPaint
        )
    }

    private fun baselineForCenteredText(
        paint: android.graphics.Paint,
        top: Float,
        bottom: Float
    ): Float {
        val metrics = paint.fontMetrics
        return (top + bottom) / 2f - (metrics.ascent + metrics.descent) / 2f
    }

    private fun fitText(
        text: String,
        paint: android.graphics.Paint,
        maxWidth: Float
    ): String {
        if (text.isBlank() || maxWidth <= 0f || paint.measureText(text) <= maxWidth) {
            return text
        }

        var end = text.length
        while (end > 0 && paint.measureText(text.substring(0, end).trimEnd() + "...") > maxWidth) {
            end--
        }
        return if (end <= 0) "" else text.substring(0, end).trimEnd() + "..."
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
