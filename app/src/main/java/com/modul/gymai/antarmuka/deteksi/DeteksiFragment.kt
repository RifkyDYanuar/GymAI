package com.modul.gymai.antarmuka.deteksi

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.modul.gymai.camera.CameraManager
import com.modul.gymai.data.GymDatabase
import com.modul.gymai.data.WorkoutRepository
import com.modul.gymai.data.WorkoutSession
import com.modul.gymai.databinding.FragmentDeteksiBinding
import com.modul.gymai.pose.PoseResult
import com.modul.gymai.pose.YoloPoseDetector
import com.modul.gymai.processing.BicepCurlRuleEngine
import com.modul.gymai.processing.ExerciseType
import com.modul.gymai.processing.LateralRaiseRuleEngine
import com.modul.gymai.processing.RepetitionCounter
import com.modul.gymai.processing.SequenceBuffer
import com.modul.gymai.processing.ShoulderPressRuleEngine
import com.modul.gymai.processing.SquatRuleEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class DeteksiFragment : Fragment() {

    companion object {
        private const val TAG = "DeteksiFragment"
        private const val ARG_EXERCISE_TYPE = "exerciseType"
    }

    private var _binding: FragmentDeteksiBinding? = null
    private val binding
        get() = _binding!!

    // Selected exercise (received from LatihanFragment)
    private lateinit var exerciseType: ExerciseType

    // ML Components
    private lateinit var poseDetector: YoloPoseDetector
    private lateinit var sequenceBuffer: SequenceBuffer
    private lateinit var repCounter: RepetitionCounter

    // Rule Engines
    private var squatEngine: SquatRuleEngine? = null
    private var curlEngine: BicepCurlRuleEngine? = null
    private var raiseEngine: LateralRaiseRuleEngine? = null
    private var pressEngine: ShoulderPressRuleEngine? = null

    // State for motion detection
    private var lastPose: PoseResult? = null
    private var staticFrameCount = 0
    private val STATIC_THRESHOLD = 0.015f // Distance threshold
    private val STATIC_FRAMES_LIMIT = 45 // ~1.5 seconds at 30fps

    // Pipeline State
    private var isDetecting = false
    private var sessionStartTime = 0L
    private var confidenceSum = 0f
    private var confidenceCount = 0
    private var lastFpsTime = SystemClock.elapsedRealtime()
    private var frameCount = 0
    private var detectionState = DetectionState.RULES_OVERLAY
    private var rulesStartTime = 0L
    private var countdownStartTime = 0L
    private var missingFrameCount = 0

    // Feedback tracking
    private val feedbackMap = mutableMapOf<String, Int>()

    private val scope = CoroutineScope(Dispatchers.IO + Job())

    // Camera
    private lateinit var cameraManager: CameraManager

    enum class DetectionState {
        RULES_OVERLAY,
        COUNTDOWN_5S,
        EVALUATING
    }

    private val requestPermissionLauncher =
            registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                if (granted) startDetection()
                else {
                    Toast.makeText(
                                    requireContext(),
                                    "Izin kamera diperlukan untuk memulai deteksi",
                                    Toast.LENGTH_LONG
                            )
                            .show()
                    findNavController().navigateUp()
                }
            }

    override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDeteksiBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Read exercise type from navigation arguments
        val typeArg = arguments?.getString(ARG_EXERCISE_TYPE) ?: ExerciseType.SQUAT.name
        exerciseType = ExerciseType.fromString(typeArg)

        initMLComponents()
        setupHeader()

        binding.btnStop.setOnClickListener { stopAndSaveSession() }

        binding.btnSwitchCamera.setOnClickListener {
            if (::cameraManager.isInitialized) {
                cameraManager.toggleCamera()
                binding.overlayView.setFrontCamera(cameraManager.isFrontCamera())
            }
        }

        checkCameraPermission()
    }

    /** Set up the top bar with exercise name. */
    private fun setupHeader() {
        binding.tvExerciseName.text = "Deteksi ${exerciseType.displayName}"
    }

    private fun checkCameraPermission() {
        when {
            ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED -> startDetection()
            else -> requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startDetection() {
        isDetecting = true
        sessionStartTime = SystemClock.elapsedRealtime()
        repCounter.reset()
        sequenceBuffer.clear()

        // Also reset stateful rule engines
        squatEngine?.reset()
        curlEngine?.reset()
        raiseEngine?.reset()
        pressEngine?.reset()
        feedbackMap.clear()

        cameraManager =
                CameraManager(
                        context = requireContext(),
                        lifecycleOwner = viewLifecycleOwner,
                        previewView = binding.previewView,
                        onFrameReady = { bitmap -> processFrame(bitmap) }
                )
        cameraManager.startCamera()
        binding.overlayView.setFrontCamera(cameraManager.isFrontCamera())
        binding.layoutInitializing.visibility = View.GONE

        // Start with rules overlay for 10 seconds
        detectionState = DetectionState.RULES_OVERLAY
        rulesStartTime = SystemClock.elapsedRealtime()
        binding.layoutRulesOverlay.visibility = View.VISIBLE
    }

    /** Initialize all components for the selected exercise type. */
    private fun initMLComponents() {
        poseDetector = YoloPoseDetector(requireContext())
        sequenceBuffer = SequenceBuffer(96)
        repCounter = RepetitionCounter(exerciseType)

        // Instantiate only the relevant rule engine
        when (exerciseType) {
            ExerciseType.SQUAT -> squatEngine = SquatRuleEngine()
            ExerciseType.BICEP_CURL -> curlEngine = BicepCurlRuleEngine()
            ExerciseType.LATERAL_RAISE -> raiseEngine = LateralRaiseRuleEngine()
            ExerciseType.SHOULDER_PRESS -> pressEngine = ShoulderPressRuleEngine()
        }
    }

    /**
     * Full ML pipeline per frame:
     * 1. YOLO pose estimation
     * 2. Visibility check
     * 3. Static pose check
     * 4. Rule engine validation (exercise-specific)
     * 5. UI update on main thread (no skeleton drawn)
     */
    private fun processFrame(bitmap: android.graphics.Bitmap) {
        if (!isDetecting) return

        // Track FPS
        frameCount++
        val now = SystemClock.elapsedRealtime()
        val elapsed = now - lastFpsTime
        if (elapsed >= 1000) {
            val fps = frameCount * 1000f / elapsed
            frameCount = 0
            lastFpsTime = now
            updateFps(fps)
        }

        // Logic based on DetectionState
        when (detectionState) {
            DetectionState.RULES_OVERLAY -> {
                val rulesElapsed = SystemClock.elapsedRealtime() - rulesStartTime
                val remaining = 10 - (rulesElapsed / 1000).toInt()

                requireActivity().runOnUiThread {
                    val b = _binding ?: return@runOnUiThread
                    if (remaining <= 0) {
                        b.layoutRulesOverlay.visibility = View.GONE
                        detectionState = DetectionState.COUNTDOWN_5S
                        countdownStartTime = SystemClock.elapsedRealtime()
                    } else {
                        b.tvRulesCountdown.text = remaining.toString()
                    }
                }
                updateUi("--", 0f, "Pelajari panduan penempatan", false, null)
                return
            }
            DetectionState.COUNTDOWN_5S -> {
                val countdownElapsed = SystemClock.elapsedRealtime() - countdownStartTime
                val remaining = 5 - (countdownElapsed / 1000).toInt()

                if (remaining <= 0) {
                    detectionState = DetectionState.EVALUATING
                    repCounter.reset()
                    sequenceBuffer.clear()
                } else {
                    updateUi("BERSIAP", 0f, "Mulai dalam $remaining detik...", true, null)
                    return
                }
            }
            DetectionState.EVALUATING -> {
                val isFront = cameraManager.isFrontCamera()
                val pose = poseDetector.detect(bitmap, isFront)

                if (pose == null || !pose.isValid()) {
                    missingFrameCount++
                    if (missingFrameCount > 10) {
                        updateUi("HILANG", 0f, "Objek tidak terdeteksi di kamera", false, null)
                    }
                    return
                }
                missingFrameCount = 0

                // Motion Detection
                if (lastPose != null) {
                    val movement = calculateMovement(pose, lastPose!!)
                    if (movement < STATIC_THRESHOLD) {
                        staticFrameCount++
                    } else {
                        staticFrameCount = 0
                    }
                }
                lastPose = pose

                if (staticFrameCount > STATIC_FRAMES_LIMIT) {
                    updateUi(
                            "DIAM",
                            pose.score,
                            "Belum ada gerakan terdeteksi",
                            false,
                            null
                    ) // User asked: "diam ... tidak terdeteksi bergerak"
                    return
                }

                // Rule-based Validation
                val (isValid, feedback) = validateWithRuleEngine(pose)

                // Repetition Counting (Hanya dihitung jika gerakan BENAR)
                if (isValid) {
                    updateRepCounter(pose)
                }

                val label = if (isValid) "BENAR" else "SALAH"
                updateUi(
                        label,
                        pose.score,
                        feedback,
                        isValid,
                        null
                ) // null poseResult to NOT draw skeleton
            }
        }
    }

    private fun calculateMovement(p1: PoseResult, p2: PoseResult): Float {
        var totalDist = 0f
        var count = 0
        for (i in p1.keypoints.indices) {
            val k1 = p1.keypoints[i]
            val k2 = p2.keypoints[i]
            if (k1.confidence > 0.45f && k2.confidence > 0.45f) {
                val dx = k1.x - k2.x
                val dy = k1.y - k2.y
                totalDist += kotlin.math.sqrt(dx * dx + dy * dy)
                count++
            }
        }
        return if (count > 0) totalDist / count else 0f
    }

    private fun updateRepCounter(pose: PoseResult) {
        val angle =
                when (exerciseType) {
                    ExerciseType.SQUAT -> squatEngine?.computeKneeAngle(pose) ?: 180f
                    ExerciseType.BICEP_CURL -> curlEngine?.computeElbowAngle(pose) ?: 180f
                    ExerciseType.LATERAL_RAISE -> raiseEngine?.computeShoulderAngle(pose) ?: 0f
                    ExerciseType.SHOULDER_PRESS -> pressEngine?.computeAvgElbowAngle(pose) ?: 0f
                }
        repCounter.onNewFrame(pose, angle)
    }

    /** Delegate validation to the appropriate rule engine. Returns (isValid, feedbackMessage). */
    private fun validateWithRuleEngine(pose: PoseResult): Pair<Boolean, String> {
        return when (exerciseType) {
            ExerciseType.SQUAT -> {
                val result = squatEngine!!.validate(pose)
                Pair(result.isValid, result.feedback)
            }
            ExerciseType.BICEP_CURL -> {
                val result = curlEngine!!.validate(pose)
                Pair(result.isValid, result.feedback)
            }
            ExerciseType.LATERAL_RAISE -> {
                val result = raiseEngine!!.validate(pose)
                Pair(result.isValid, result.feedback)
            }
            ExerciseType.SHOULDER_PRESS -> {
                val result = pressEngine!!.validate(pose)
                Pair(result.isValid, result.feedback)
            }
        }
    }

    private fun updateUi(
            label: String,
            confidence: Float,
            feedback: String,
            isBenar: Boolean,
            poseResult: PoseResult?
    ) {
        requireActivity().runOnUiThread {
            val b = _binding ?: return@runOnUiThread

            b.tvLabel.text = label
            b.tvLabel.background =
                    ContextCompat.getDrawable(
                            requireContext(),
                            if (isBenar) com.modul.gymai.R.drawable.bg_chip_benar
                            else com.modul.gymai.R.drawable.bg_chip_salah
                    )
            b.tvLabel.setTextColor(
                    if (isBenar) Color.parseColor("#22C55E") else Color.parseColor("#EF4444")
            )

            val pct = (confidence * 100).toInt()
            b.tvConfidence.text = "$pct%"
            b.progressConfidence.progress = pct

            b.tvReps.text = repCounter.getRepCount().toString()
            b.tvFeedback.text = feedback

            b.overlayView.updatePose(poseResult, isBenar)
        }
    }

    private fun updateFps(fps: Float) {
        requireActivity().runOnUiThread { _binding?.tvFps?.text = "${fps.toInt()} FPS" }
    }

    private fun stopAndSaveSession() {
        isDetecting = false
        cameraManager.release()

        val duration = (SystemClock.elapsedRealtime() - sessionStartTime) / 1000
        val avgConf = if (confidenceCount > 0) confidenceSum / confidenceCount else 0f
        val totalReps = repCounter.getRepCount()

        // Determine most frequent feedback
        val mostFrequentFeedback = feedbackMap.maxByOrNull { it.value }?.key

        scope.launch {
            val db = GymDatabase.getInstance(requireContext())
            val repo = WorkoutRepository(db.workoutSessionDao())
            repo.insertSession(
                    WorkoutSession(
                            totalReps = totalReps,
                            averageConfidence = avgConf,
                            durationSeconds = duration,
                            exerciseType = exerciseType.name,
                            mostFrequentFeedback = mostFrequentFeedback
                    )
            )
        }

        requireActivity().runOnUiThread {
            if (_binding != null) {
                findNavController().navigateUp()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        isDetecting = false
        if (::cameraManager.isInitialized) cameraManager.release()
        if (::poseDetector.isInitialized) poseDetector.close()
        _binding = null
    }
}
