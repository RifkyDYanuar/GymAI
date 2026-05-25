package com.modul.gymai.camera

import android.content.Context
import android.os.Looper
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.PendingRecording
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Manages CameraX lifecycle: Preview + ImageAnalysis.
 *
 * - Preview binds to PreviewView (UI).
 * - ImageAnalysis provides ImageProxy frames for pose inference.
 * - Optimized for ML Kit Pose Detection.
 * - Mendukung YOLO_PERFORMANCE mode dengan resolusi analysis 640×480.
 */
class CameraManager(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val previewView: PreviewView,
    private val onFrameReady: (ImageProxy) -> Unit
) {

    enum class PerformanceMode {
        DEFAULT,
        LIVE_PERFORMANCE,
        CONSERVATIVE_LIVE_PERFORMANCE,
        /** Mode khusus YOLO: resolusi analysis 640×480 agar tidak upscale ke 640×640. */
        YOLO_PERFORMANCE
    }

    companion object {
        private const val TAG = "CameraManager"
        private const val MAIN_THREAD_BIND_TIMEOUT_MS = 5000L

        private data class CameraPerformanceProfile(
            val backPreviewResolutions: List<Size>,
            val frontPreviewResolutions: List<Size>,
            val backAnalysisResolutions: List<Size>,
            val frontAnalysisResolutions: List<Size>
        )

        private val DEFAULT_CAMERA_PROFILE = CameraPerformanceProfile(
            backPreviewResolutions = listOf(
                Size(1280, 720),
                Size(960, 540),
                Size(640, 480),
            ),
            frontPreviewResolutions = listOf(
                Size(960, 540),
                Size(640, 480),
            ),
            backAnalysisResolutions = listOf(
                Size(320, 240),
                Size(480, 360),
            ),
            frontAnalysisResolutions = listOf(
                Size(320, 240),
                Size(480, 360),
            )
        )

        private val LIVE_PERFORMANCE_PROFILE = CameraPerformanceProfile(
            backPreviewResolutions = listOf(
                Size(1280, 720),
                Size(960, 540),
            ),
            frontPreviewResolutions = listOf(
                Size(960, 540),
                Size(640, 480),
            ),
            backAnalysisResolutions = listOf(
                Size(320, 240),
            ),
            frontAnalysisResolutions = listOf(
                Size(320, 240),
            )
        )

        private val CONSERVATIVE_LIVE_PERFORMANCE_PROFILE = CameraPerformanceProfile(
            backPreviewResolutions = listOf(
                Size(960, 540),
                Size(640, 480),
            ),
            frontPreviewResolutions = listOf(
                Size(640, 480),
            ),
            backAnalysisResolutions = listOf(
                Size(320, 240),
            ),
            frontAnalysisResolutions = listOf(
                Size(320, 240),
            )
        )

        /**
         * Profil YOLO: analysis 640×480 agar tidak perlu upscale ke 640×640.
         * Preview tetap 1280×720 untuk tampilan tajam di layar.
         * Fallback ke 640×360 jika device tidak support 640×480 analysis.
         */
        private val YOLO_PERFORMANCE_PROFILE = CameraPerformanceProfile(
            backPreviewResolutions = listOf(
                Size(1280, 720),
                Size(960, 540),
            ),
            frontPreviewResolutions = listOf(
                Size(960, 540),
                Size(640, 480),
            ),
            backAnalysisResolutions = listOf(
                Size(640, 480),
                Size(640, 360),
            ),
            frontAnalysisResolutions = listOf(
                Size(640, 480),
                Size(640, 360),
            )
        )

        private val FRONT_CAMERA_RECORDING_PREVIEW_RESOLUTIONS = listOf(
            Size(640, 480),
        )
        private val LOW_LOAD_ANALYSIS_RESOLUTIONS = listOf(
            Size(320, 240),
        )
        private val FRONT_CAMERA_RECORDING_ANALYSIS_RESOLUTIONS = listOf(
            Size(320, 240),
        )
    }

    private var cameraProvider: ProcessCameraProvider? = null
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainExecutor = ContextCompat.getMainExecutor(context)
    private val recordingStateLock = Any()
    private var boundCamera: Camera? = null
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private var recordingOutputFile: File? = null
    private var recordingFinalizeCallback: ((String?) -> Unit)? = null
    private var shouldBindVideoCapture = false
    private var performanceMode = PerformanceMode.DEFAULT
    @Volatile
    private var isPreparingEvaluationRecording = false

    fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                bindCameraUseCasesOnMainThread()
            } catch (e: Exception) {
                Log.e(TAG, "Camera init failed: ${e.message}")
            }
        }, mainExecutor)
    }

    private fun bindCameraUseCases(): Boolean {
        val cameraProvider = this.cameraProvider ?: run {
            Log.w(TAG, "Camera provider is not ready yet")
            return false
        }

        val cameraSelector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
        val targetRotation = previewView.display?.rotation ?: Surface.ROTATION_0
        val shouldAttachVideoCapture = shouldAttachVideoCapture()

        for ((profileName, resolutionPairs) in buildResolutionPairCandidates()) {
            for ((previewSize, analysisSize) in resolutionPairs) {
                try {
                    val preview = Preview.Builder()
                        .setTargetRotation(targetRotation)
                        .setTargetResolution(previewSize)
                        .build()
                        .also {
                            it.surfaceProvider = previewView.surfaceProvider
                        }

                    val imageAnalysis = ImageAnalysis.Builder()
                        .setTargetRotation(targetRotation)
                        .setTargetResolution(analysisSize)
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .also { analysis ->
                            analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                                onFrameReady(imageProxy)
                            }
                        }

                    val useCases = mutableListOf<UseCase>(preview, imageAnalysis)

                    if (shouldAttachVideoCapture) {
                        val recorder = Recorder.Builder()
                            .setQualitySelector(
                                QualitySelector.fromOrderedList(
                                    preferredVideoQualities(),
                                    FallbackStrategy.lowerQualityOrHigherThan(Quality.LOWEST)
                                )
                            )
                            .build()
                        val videoCapture = VideoCapture.withOutput(recorder).also {
                            it.targetRotation = targetRotation
                        }
                        this.videoCapture = videoCapture
                        useCases += videoCapture
                    } else {
                        this.videoCapture = null
                    }

                    cameraProvider.unbindAll()
                    boundCamera = cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        cameraSelector,
                        *useCases.toTypedArray()
                    )
                    applyExposureForCurrentLens()
                    applyMinimumZoomForCurrentLens()
                    Log.d(
                        TAG,
                        "Camera bound successfully. Profile=$profileName, " +
                            "Preview=${previewSize.width}x${previewSize.height}, " +
                            "Analysis=${analysisSize.width}x${analysisSize.height}, VideoCapture=$shouldAttachVideoCapture"
                    )
                    return true
                } catch (e: Exception) {
                    Log.w(
                        TAG,
                        "Use case bind failed for Profile=$profileName, " +
                            "Preview=${previewSize.width}x${previewSize.height}, " +
                            "Analysis=${analysisSize.width}x${analysisSize.height}: ${e.message}"
                    )
                }
            }
        }

        Log.e(TAG, "Use case binding failed for all configured resolution fallbacks")
        return false
    }

    private fun buildResolutionPairs(profile: CameraPerformanceProfile): List<Pair<Size, Size>> {
        val pairs = mutableListOf<Pair<Size, Size>>()
        val previewResolutions = preferredPreviewResolutions(profile)
        val analysisResolutions = preferredAnalysisResolutions(profile)
        for (previewSize in previewResolutions) {
            for (analysisSize in analysisResolutions) {
                pairs += previewSize to analysisSize
            }
        }
        return pairs
    }

    private fun buildResolutionPairCandidates(): List<Pair<String, List<Pair<Size, Size>>>> {
        return profileCandidates().map { (name, profile) ->
            name to buildResolutionPairs(profile)
        }
    }

    private fun shouldAttachVideoCapture(): Boolean = shouldBindVideoCapture

    private fun preferredPreviewResolutions(profile: CameraPerformanceProfile): List<Size> {
        return when {
            lensFacing == CameraSelector.LENS_FACING_FRONT && shouldBindVideoCapture ->
                FRONT_CAMERA_RECORDING_PREVIEW_RESOLUTIONS
            lensFacing == CameraSelector.LENS_FACING_FRONT -> profile.frontPreviewResolutions
            shouldBindVideoCapture -> profile.backPreviewResolutions
            else -> profile.backPreviewResolutions
        }
    }

    private fun preferredAnalysisResolutions(profile: CameraPerformanceProfile): List<Size> {
        return when {
            lensFacing == CameraSelector.LENS_FACING_FRONT && shouldBindVideoCapture ->
                FRONT_CAMERA_RECORDING_ANALYSIS_RESOLUTIONS
            lensFacing == CameraSelector.LENS_FACING_FRONT || shouldBindVideoCapture ->
                (LOW_LOAD_ANALYSIS_RESOLUTIONS + profile.frontAnalysisResolutions)
            else -> profile.backAnalysisResolutions
        }.distinct()
    }

    private fun profileCandidates(): List<Pair<String, CameraPerformanceProfile>> {
        return when (performanceMode) {
            PerformanceMode.DEFAULT -> listOf(
                "default" to DEFAULT_CAMERA_PROFILE,
                "live_performance" to LIVE_PERFORMANCE_PROFILE
            )
            PerformanceMode.LIVE_PERFORMANCE -> listOf(
                "live_performance" to LIVE_PERFORMANCE_PROFILE,
                "conservative_live_performance" to CONSERVATIVE_LIVE_PERFORMANCE_PROFILE
            )
            PerformanceMode.CONSERVATIVE_LIVE_PERFORMANCE -> listOf(
                "conservative_live_performance" to CONSERVATIVE_LIVE_PERFORMANCE_PROFILE
            )
            PerformanceMode.YOLO_PERFORMANCE -> listOf(
                "yolo_performance" to YOLO_PERFORMANCE_PROFILE,
                "live_performance" to LIVE_PERFORMANCE_PROFILE
            )
        }
    }

    private fun preferredVideoQualities(): List<Quality> {
        return listOf(Quality.LOWEST)
    }

    private fun bindCameraUseCasesOnMainThread(): Boolean {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return bindCameraUseCases()
        }

        val latch = CountDownLatch(1)
        var success = false
        mainExecutor.execute {
            try {
                success = bindCameraUseCases()
            } finally {
                latch.countDown()
            }
        }

        val completed = latch.await(MAIN_THREAD_BIND_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        if (!completed) {
            Log.e(TAG, "Timed out while waiting for camera use cases to bind on main thread")
        }
        return completed && success
    }

    private fun applyMinimumZoomForCurrentLens() {
        val camera = boundCamera ?: return
        val zoomState = camera.cameraInfo.zoomState.value ?: run {
            Log.d(TAG, "Zoom state is not available for current camera")
            return
        }
        val minimumZoomRatio = zoomState.minZoomRatio
        if (!minimumZoomRatio.isFinite() || minimumZoomRatio <= 0f) {
            Log.d(TAG, "Ignoring unsupported minimum zoom ratio: $minimumZoomRatio")
            return
        }
        if (zoomState.zoomRatio <= minimumZoomRatio + 0.01f) {
            return
        }

        val zoomFuture = camera.cameraControl.setZoomRatio(minimumZoomRatio)
        zoomFuture.addListener(
            {
                runCatching {
                    zoomFuture.get()
                }.onSuccess {
                    Log.d(TAG, "Applied minimum zoom ratio: $minimumZoomRatio")
                }.onFailure { error ->
                    Log.w(TAG, "Failed to apply minimum zoom ratio: ${error.message}")
                }
            },
            mainExecutor
        )
    }

    fun stopCamera() {
        cameraProvider?.unbindAll()
        boundCamera = null
    }

    fun toggleCamera() {
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }
        bindCameraUseCasesOnMainThread()
    }

    fun isFrontCamera(): Boolean = lensFacing == CameraSelector.LENS_FACING_FRONT

    fun setPerformanceMode(mode: PerformanceMode) {
        if (performanceMode == mode) return
        performanceMode = mode
        if (cameraProvider != null) {
            bindCameraUseCasesOnMainThread()
        }
    }

    fun isEvaluationRecording(): Boolean = activeRecording != null

    fun isPreparingEvaluationRecording(): Boolean = isPreparingEvaluationRecording

    private fun tryBeginEvaluationRecordingPreparation(): Boolean = synchronized(recordingStateLock) {
        if (activeRecording != null || isPreparingEvaluationRecording) {
            false
        } else {
            isPreparingEvaluationRecording = true
            true
        }
    }

    private fun finishEvaluationRecordingPreparation() {
        synchronized(recordingStateLock) {
            isPreparingEvaluationRecording = false
        }
    }

    fun startEvaluationRecording(
        outputFile: File,
        onFinalized: (String?) -> Unit
    ) {
        if (!tryBeginEvaluationRecordingPreparation()) return

        try {
            if (!shouldBindVideoCapture || videoCapture == null) {
                shouldBindVideoCapture = true
                val bindSucceeded = bindCameraUseCasesOnMainThread()
                if (!bindSucceeded) {
                    Log.e(TAG, "Failed to bind VideoCapture for evaluation recording")
                    onFinalized(null)
                    return
                }
            }

            val videoCapture = videoCapture ?: run {
                Log.e(TAG, "VideoCapture is unavailable after bind attempt")
                onFinalized(null)
                return
            }

            outputFile.parentFile?.mkdirs()
            recordingOutputFile = outputFile
            recordingFinalizeCallback = onFinalized

            val outputOptions = FileOutputOptions.Builder(outputFile).build()
            val pendingRecording: PendingRecording =
                videoCapture.output.prepareRecording(context, outputOptions)

            activeRecording = pendingRecording.start(mainExecutor) { event ->
                if (event is VideoRecordEvent.Finalize) {
                    val callback = recordingFinalizeCallback
                    val resultPath = if (!event.hasError()) {
                        recordingOutputFile?.absolutePath
                    } else {
                        runCatching { recordingOutputFile?.delete() }
                        null
                    }

                    activeRecording?.close()
                    activeRecording = null
                    recordingOutputFile = null
                    recordingFinalizeCallback = null
                    finishEvaluationRecordingPreparation()
                    callback?.invoke(resultPath)
                }
            }
            finishEvaluationRecordingPreparation()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start evaluation recording: ${e.message}", e)
            runCatching { recordingOutputFile?.delete() }
            activeRecording = null
            recordingOutputFile = null
            recordingFinalizeCallback = null
            onFinalized(null)
        } finally {
            finishEvaluationRecordingPreparation()
        }
    }

    fun stopEvaluationRecording(onFinalized: (String?) -> Unit) {
        val recording = activeRecording
        if (recording == null) {
            onFinalized(null)
            return
        }

        val previousCallback = recordingFinalizeCallback
        recordingFinalizeCallback = { path ->
            previousCallback?.invoke(path)
            onFinalized(path)
        }
        recording.stop()
    }

    fun cancelEvaluationRecording() {
        runCatching { activeRecording?.close() }
        runCatching { recordingOutputFile?.delete() }
        activeRecording = null
        recordingOutputFile = null
        recordingFinalizeCallback = null
        finishEvaluationRecordingPreparation()
    }

    fun release() {
        cancelEvaluationRecording()
        stopCamera()
        cameraExecutor.shutdown()
    }

    private fun applyExposureForCurrentLens() {
        val camera = boundCamera ?: return
        val exposureState = camera.cameraInfo.exposureState
        if (!exposureState.isExposureCompensationSupported) return

        // Neutral exposure is more stable across devices. Some front cameras turned
        // dark or black when a positive compensation index was forced after rebinding.
        val targetIndex = 0

        if (exposureState.exposureCompensationIndex == targetIndex) return

        camera.cameraControl.setExposureCompensationIndex(targetIndex)
    }
}
