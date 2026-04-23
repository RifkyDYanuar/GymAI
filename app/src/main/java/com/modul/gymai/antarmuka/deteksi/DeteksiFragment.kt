package com.modul.gymai.antarmuka.deteksi

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.CountDownTimer
import android.os.SystemClock
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
import com.modul.gymai.pose.PoseDetectorHelper
import com.modul.gymai.pose.PoseResult
import com.modul.gymai.engine.*
import com.modul.gymai.pose.Keypoint
import com.modul.gymai.ui.MaterialSymbols
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
        private const val BICEP_RESULT_DISPLAY_MS = 700L
        private const val RULES_COUNTDOWN_SECONDS = 10
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
    private var lastFpsTime = SystemClock.elapsedRealtime()
    private var frameCount = 0
    private var detectionState = DetectionState.RULES_OVERLAY
    private var missingFrameCount = 0

    private val feedbackMap = mutableMapOf<String, Int>()
    private var lastUiUpdateTime = 0L
    private val scope = CoroutineScope(Dispatchers.IO + Job())

    private var lastKeypoints: List<Keypoint>? = null
    private var stillnessCount = 0
    private var bicepResultDisplayUntil = 0L
    private var bicepResultLabel = "SIAP"
    private var bicepResultFeedback = "Siap untuk repetisi berikutnya"
    private var bicepResultCorrect = true
    private var evaluationVideoPath: String? = null
    private var realtimeTtsManager: RealtimeTtsManager? = null
    private var hasRequestedEvaluationRecordingStart = false
    private var hasEvaluationRecordingFailed = false
    private var hasSpokenRulesGuidance = false
    private var isSavingSession = false
    private var rulesCountdownTimer: CountDownTimer? = null
    private var isRulesCountdownRunning = false
    private var isRulesCountdownIntroPending = false
    private var lastAnimatedRulesCountdownValue = -1
    private var lastSpokenRulesCountdownValue = -1

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
        binding.previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
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
    }

    @OptIn(ExperimentalGetImage::class)
    private fun startDetection() {
        isDetecting = true
        sessionStartTime = SystemClock.elapsedRealtime()
        repCounter.reset()
        engine.reset()
        feedbackMap.clear()
        evaluationVideoPath = null
        hasRequestedEvaluationRecordingStart = false
        hasEvaluationRecordingFailed = false
        hasSpokenRulesGuidance = false
        isSavingSession = false
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
        cameraManager.startCamera()
        binding.overlayView.setFrontCamera(cameraManager.isFrontCamera())
        binding.layoutInitializing.visibility = View.GONE

        detectionState = DetectionState.RULES_OVERLAY
        binding.layoutRulesOverlay.visibility = View.VISIBLE
        binding.tvRulesCountdown.visibility = View.GONE
        binding.tvRulesReadyHint.text = "Panduan sedang dibacakan, tunggu sebentar"
        binding.tvLabel.text = "SIAP"
        binding.tvFeedback.text = "Dengarkan panduan penempatan sebelum evaluasi dimulai"
        speakRulesGuidanceIfNeeded()
    }

    @OptIn(ExperimentalGetImage::class)
    private fun processFrame(imageProxy: ImageProxy) {
        // Track FPS
        frameCount++
        val now = SystemClock.elapsedRealtime()
        if (now - lastFpsTime >= 1000) {
            val fps = frameCount * 1000f / (now - lastFpsTime)
            frameCount = 0
            lastFpsTime = now
            requireActivity().runOnUiThread { _binding?.tvFps?.text = "${fps.toInt()} FPS" }
        }

        when (detectionState) {
            DetectionState.RULES_OVERLAY,
            DetectionState.RULES_COUNTDOWN -> {
                imageProxy.close()
            }
            DetectionState.EVALUATING -> {
                startEvaluationRecordingIfNeeded()
                poseDetectorHelper.detect(imageProxy)
            }
        }
    }

    private fun announceRulesCountdownIntroIfNeeded() {
        if (detectionState == DetectionState.EVALUATING || isRulesCountdownRunning || isRulesCountdownIntroPending) return
        isRulesCountdownIntroPending = true
        _binding?.tvRulesReadyHint?.text = "Evaluasi akan dimulai dalam 10 detik"
        realtimeTtsManager?.speakIfEligible(
            message = "Evaluasi akan dimulai dalam sepuluh detik",
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

        val result = engine.validate(pose)
        if (exerciseType == ExerciseType.BICEP_CURL) {
            if (result.repCompleted) {
                repCounter.onBicepRepCompleted(result.shouldCountRep)
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
        val uiState = if (exerciseType == ExerciseType.BICEP_CURL) {
            if (result.repCompleted && !result.shouldCountRep) {
                feedbackMap[result.feedback] = feedbackMap.getOrDefault(result.feedback, 0) + 1
            }
            buildBicepUiState(result)
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

    private fun buildBicepUiState(result: RuleResult): DetectionUiState {
        val now = SystemClock.elapsedRealtime()

        if (result.repStatus == BicepRepStatus.REP_GOOD || result.repStatus == BicepRepStatus.REP_BAD) {
            bicepResultDisplayUntil = now + BICEP_RESULT_DISPLAY_MS
            bicepResultLabel = if (result.repStatus == BicepRepStatus.REP_GOOD) "BENAR" else "SALAH"
            bicepResultFeedback = result.feedback
            bicepResultCorrect = result.repStatus == BicepRepStatus.REP_GOOD
        }

        if (now < bicepResultDisplayUntil) {
            return DetectionUiState(
                label = bicepResultLabel,
                feedback = bicepResultFeedback,
                isCorrect = bicepResultCorrect
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

    private fun updateUi(label: String, confidence: Float, feedback: String, isCorrect: Boolean, pose: PoseResult?) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastUiUpdateTime < 33L) return
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

    private fun speakRulesGuidanceIfNeeded() {
        if (hasSpokenRulesGuidance) return
        hasSpokenRulesGuidance = true
        val guidanceSpeech = buildString {
            append("Panduan penempatan. ")
            append("Cahaya terang, pastikan tubuh terlihat jelas. ")
            append("Jarak, berdiri dua sampai tiga meter dari kamera. ")
            append("Seluruh tubuh, kepala hingga kaki harus terlihat. ")
            append("Stabil, letakkan handphone pada posisi tegak dan diam. ")
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

        if (exerciseType == ExerciseType.BICEP_CURL && result.repCompleted) {
            val repSpeech = if (result.shouldCountRep) {
                "Repetisi benar"
            } else {
                val correction = mapFeedbackToSpeech(result.feedback)
                if (correction != null) {
                    "Repetisi salah. $correction"
                } else {
                    "Repetisi salah"
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
            exerciseType == ExerciseType.BICEP_CURL && uiState.label == "GERAKAN" ->
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
            "Tempo terlalu cepat, perlambat gerakan" -> "Tempo terlalu cepat, perlambat gerakan"
            "Jaga siku tetap diam di samping tubuh" -> "Jaga siku tetap diam di samping tubuh"
            "Jaga tubuh tetap tegak, jangan terlalu bergoyang" -> "Jaga tubuh tetap tegak, jangan terlalu bergoyang"
            "Angkat beban sedikit lebih tinggi" -> "Angkat beban sedikit lebih tinggi"
            "Kaki tidak terdeteksi" -> "Kaki tidak terdeteksi"
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
            "Angkat beban sedikit lebih tinggi" -> feedback
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
        cancelRulesCountdown()
        if (::poseDetectorHelper.isInitialized) poseDetectorHelper.close()
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
        _binding = null
    }

    override fun onResume() {
        super.onResume()
        if (!isDetecting || detectionState != DetectionState.RULES_OVERLAY || hasSpokenRulesGuidance) return
        _binding?.layoutRulesOverlay?.visibility = View.VISIBLE
        _binding?.tvRulesReadyHint?.text = "Panduan sedang dibacakan, tunggu sebentar"
        speakRulesGuidanceIfNeeded()
    }

    override fun onStop() {
        super.onStop()
        if (detectionState != DetectionState.EVALUATING) {
            cancelRulesCountdown()
            detectionState = DetectionState.RULES_OVERLAY
            hasSpokenRulesGuidance = false
            _binding?.tvRulesCountdown?.visibility = View.GONE
            _binding?.tvRulesReadyHint?.text = "Panduan akan diputar ulang saat halaman dibuka kembali"
        }
        realtimeTtsManager?.stop()
    }

    private fun startEvaluationRecordingIfNeeded() {
        if (hasRequestedEvaluationRecordingStart || hasEvaluationRecordingFailed || !::cameraManager.isInitialized) return
        if (cameraManager.isPreparingEvaluationRecording() || cameraManager.isEvaluationRecording()) return

        val externalDir = requireContext().getExternalFilesDir("evaluations")
        val baseDir = externalDir ?: File(requireContext().filesDir, "evaluations")
        baseDir.mkdirs()
        val outputFile = File(
            baseDir,
            "${System.currentTimeMillis()}_${exerciseType.name.lowercase(Locale.US)}.mp4"
        )
        val requestedPath = outputFile.absolutePath

        evaluationVideoPath = requestedPath
        hasRequestedEvaluationRecordingStart = true
        cameraManager.startEvaluationRecording(outputFile) { finalizedPath ->
            if (!isSavingSession && finalizedPath == null && evaluationVideoPath == requestedPath) {
                evaluationVideoPath = null
                hasEvaluationRecordingFailed = true
            } else if (!finalizedPath.isNullOrBlank()) {
                evaluationVideoPath = finalizedPath
            }
        }
    }

    private suspend fun stopEvaluationRecordingAndFinalize(): String? {
        val currentPath = evaluationVideoPath
        if (!::cameraManager.isInitialized) return currentPath
        if (!cameraManager.isEvaluationRecording() && !cameraManager.isPreparingEvaluationRecording()) {
            return currentPath
        }

        return suspendCancellableCoroutine { continuation ->
            cameraManager.stopEvaluationRecording { finalizedPath ->
                val resolvedPath = finalizedPath ?: currentPath
                if (!finalizedPath.isNullOrBlank()) {
                    evaluationVideoPath = finalizedPath
                }
                if (continuation.isActive) {
                    continuation.resume(resolvedPath)
                }
            }
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
