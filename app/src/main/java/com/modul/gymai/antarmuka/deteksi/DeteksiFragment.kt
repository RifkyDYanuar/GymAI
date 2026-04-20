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
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageProxy
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.modul.gymai.camera.CameraManager
import com.modul.gymai.data.GymDatabase
import com.modul.gymai.data.WorkoutRepository
import com.modul.gymai.data.WorkoutSession
import com.modul.gymai.databinding.FragmentDeteksiBinding
import com.modul.gymai.pose.PoseDetectorHelper
import com.modul.gymai.pose.PoseResult
import com.modul.gymai.engine.*
import com.modul.gymai.pose.Keypoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class DeteksiFragment : Fragment() {

    companion object {
        private const val TAG = "DeteksiFragment"
        private const val ARG_EXERCISE_TYPE = "exerciseType"
        private const val MOVEMENT_THRESHOLD = 0.005f // Sensitivity for "Diam"
        private const val STILLNESS_LIMIT = 30 // Frames before showing "Diam"
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
    private var rulesStartTime = 0L
    private var countdownStartTime = 0L
    private var missingFrameCount = 0

    private val feedbackMap = mutableMapOf<String, Int>()
    private var lastUiUpdateTime = 0L
    private val scope = CoroutineScope(Dispatchers.IO + Job())

    private var lastKeypoints: List<Keypoint>? = null
    private var stillnessCount = 0

    enum class DetectionState {
        RULES_OVERLAY,
        COUNTDOWN_5S,
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

        initComponents()
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
        rulesStartTime = SystemClock.elapsedRealtime()
        binding.layoutRulesOverlay.visibility = View.VISIBLE
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
            DetectionState.RULES_OVERLAY -> {
                val elapsed = SystemClock.elapsedRealtime() - rulesStartTime
                val remaining = 10 - (elapsed / 1000).toInt()
                requireActivity().runOnUiThread {
                    if (remaining <= 0) {
                        binding.layoutRulesOverlay.visibility = View.GONE
                        detectionState = DetectionState.COUNTDOWN_5S
                        countdownStartTime = SystemClock.elapsedRealtime()
                    } else {
                        binding.tvRulesCountdown.text = remaining.toString()
                    }
                }
                imageProxy.close()
            }
            DetectionState.COUNTDOWN_5S -> {
                val elapsed = SystemClock.elapsedRealtime() - countdownStartTime
                val remaining = 5 - (elapsed / 1000).toInt()
                if (remaining <= 0) {
                    detectionState = DetectionState.EVALUATING
                } else {
                    updateUi("BERSIAP", 0f, "Mulai dalam $remaining detik...", true, null)
                }
                imageProxy.close()
            }
            DetectionState.EVALUATING -> {
                poseDetectorHelper.detect(imageProxy)
            }
        }
    }

    private fun processResults(pose: PoseResult) {
        if (!isDetecting) return

        if (!pose.isValid()) {
            missingFrameCount++
            // Update UI langsung tanpa buffer agar skeleton hilang seketika
            updateUi("HILANG", 0f, "Objek tidak terdeteksi", false, null)
            return
        }
        missingFrameCount = 0

        // Motion detection
        val currentKeypoints = pose.keypoints
        val movement = calculateMovement(lastKeypoints, currentKeypoints)
        lastKeypoints = currentKeypoints

        if (movement < MOVEMENT_THRESHOLD) {
            stillnessCount++
        } else {
            stillnessCount = 0
        }

        val result = engine.validate(pose)
        if (result.isValid) {
            repCounter.onNewFrame(engine.calculateMetric(pose))
        }

        confidenceSum += pose.score
        confidenceCount++
        if (!result.isValid) feedbackMap[result.feedback] = feedbackMap.getOrDefault(result.feedback, 0) + 1

        val finalFeedback = if (stillnessCount > STILLNESS_LIMIT) "Diam" else result.feedback

        updateUi(
            if (result.isValid) "BENAR" else "SALAH",
            pose.score,
            finalFeedback,
            result.isValid,
            pose
        )
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

    private fun stopAndSaveSession() {
        isDetecting = false
        if (::cameraManager.isInitialized) cameraManager.release()
        if (::poseDetectorHelper.isInitialized) poseDetectorHelper.close()

        val duration = (SystemClock.elapsedRealtime() - sessionStartTime) / 1000
        val avgConf = if (confidenceCount > 0) confidenceSum / confidenceCount else 0f
        val mostFrequentFeedback = feedbackMap.maxByOrNull { it.value }?.key

        scope.launch {
            val db = GymDatabase.getInstance(requireContext())
            val repo = WorkoutRepository(db.workoutSessionDao())
            repo.insertSession(WorkoutSession(
                totalReps = repCounter.getRepCount(),
                averageConfidence = avgConf,
                durationSeconds = duration,
                exerciseType = exerciseType.name,
                mostFrequentFeedback = mostFrequentFeedback
            ))
        }
        findNavController().navigateUp()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        isDetecting = false
        if (::cameraManager.isInitialized) cameraManager.release()
        if (::poseDetectorHelper.isInitialized) poseDetectorHelper.close()
        _binding = null
    }
}
