package com.modul.gymai.pose

import android.graphics.Bitmap
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseDetector
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions

/**
 * Helper class for ML Kit Pose Detection.
 * 
 * Note: ML Kit's InputImage with rotationDegrees automatically orients the results 
 * into the target coordinate system. Manual rotation is usually redundant and 
 * causes "horizontal" or "clustered" results.
 */
class PoseDetectorHelper(
    private val onResults: (PoseResult) -> Unit,
    private val onError: (String) -> Unit
) {
    companion object {
        private const val KEYPOINT_COUNT = 17
        private const val MIN_CONFIDENCE_TO_SMOOTH = 0.35f
        private const val STILL_ALPHA = 0.48f
        private const val DEFAULT_ALPHA = 0.78f
        private const val FAST_ALPHA = 0.96f
        private const val ARM_STILL_ALPHA = 0.62f
        private const val ARM_DEFAULT_ALPHA = 0.86f
        private const val ARM_FAST_ALPHA = 0.98f
        private const val STILL_MOVEMENT_THRESHOLD = 0.006f
        private const val FAST_MOVEMENT_THRESHOLD = 0.025f
        private const val MICRO_JITTER_THRESHOLD = 0.0012f
    }

    private var poseDetector: PoseDetector
    private var lastSmoothedKeypoints: List<Keypoint>? = null
    @Volatile private var isProcessingFrame = false

    init {
        val options = PoseDetectorOptions.Builder()
            .setDetectorMode(PoseDetectorOptions.STREAM_MODE)
            .build()
        
        poseDetector = PoseDetection.getClient(options)
    }

    @ExperimentalGetImage
    fun detect(imageProxy: ImageProxy) {
        if (isProcessingFrame) {
            imageProxy.close()
            return
        }

        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }

        isProcessingFrame = true
        val rotationDegrees = imageProxy.imageInfo.rotationDegrees
        val image = InputImage.fromMediaImage(mediaImage, rotationDegrees)
        
        // Since ML Kit handles rotation when provided to InputImage, 
        // the resulting landmarks are already in the oriented space.
        // We just need to normalize by the OREINTED dimensions.
        val isRotated = rotationDegrees == 90 || rotationDegrees == 270
        val orientedW = if (isRotated) mediaImage.height.toFloat() else mediaImage.width.toFloat()
        val orientedH = if (isRotated) mediaImage.width.toFloat() else mediaImage.height.toFloat()

        processInputImage(
            image = image,
            orientedWidth = orientedW,
            orientedHeight = orientedH,
            onFinished = { imageProxy.close() }
        )
    }

    fun detectBitmap(bitmap: Bitmap, recycleAfterUse: Boolean = true) {
        if (isProcessingFrame) {
            if (recycleAfterUse && !bitmap.isRecycled) {
                bitmap.recycle()
            }
            return
        }

        isProcessingFrame = true
        val image = InputImage.fromBitmap(bitmap, 0)
        processInputImage(
            image = image,
            orientedWidth = bitmap.width.toFloat(),
            orientedHeight = bitmap.height.toFloat(),
            onFinished = {
                if (recycleAfterUse && !bitmap.isRecycled) {
                    bitmap.recycle()
                }
            }
        )
    }

    private fun processInputImage(
        image: InputImage,
        orientedWidth: Float,
        orientedHeight: Float,
        onFinished: () -> Unit
    ) {
        poseDetector.process(image)
            .addOnSuccessListener { pose ->
                val landmarks = pose.allPoseLandmarks
                if (landmarks.isEmpty()) {
                    lastSmoothedKeypoints = null
                    onResults(emptyPoseResult())
                    isProcessingFrame = false
                    onFinished()
                    return@addOnSuccessListener
                }

                val filteredKeypoints = mutableListOf<Keypoint>()
                val mapping = listOf(0, 2, 5, 7, 8, 11, 12, 13, 14, 15, 16, 23, 24, 25, 26, 27, 28)

                var totalScore = 0f
                for (mlIndex in mapping) {
                    val landmark = pose.getPoseLandmark(mlIndex)
                    if (landmark != null) {
                        val normX = (landmark.position.x / orientedWidth).coerceIn(0f, 1f)
                        val normY = (landmark.position.y / orientedHeight).coerceIn(0f, 1f)

                        filteredKeypoints.add(Keypoint(normX, normY, landmark.inFrameLikelihood))
                        totalScore += landmark.inFrameLikelihood
                    } else {
                        filteredKeypoints.add(Keypoint(0f, 0f, 0f))
                    }
                }

                val avgScore = if (filteredKeypoints.isNotEmpty()) totalScore / filteredKeypoints.size else 0f
                val smoothedKeypoints = smoothKeypoints(filteredKeypoints)
                onResults(
                    PoseResult(
                        keypoints = smoothedKeypoints,
                        score = avgScore,
                        rawKeypoints = filteredKeypoints,
                        sourceWidth = orientedWidth,
                        sourceHeight = orientedHeight
                    )
                )
                isProcessingFrame = false
                onFinished()
            }
            .addOnFailureListener { e ->
                isProcessingFrame = false
                onError(e.message ?: "Pose detection failed")
                onFinished()
            }
    }

    private fun smoothKeypoints(currentKeypoints: List<Keypoint>): List<Keypoint> {
        val previousKeypoints = lastSmoothedKeypoints
        if (previousKeypoints == null || previousKeypoints.size != currentKeypoints.size) {
            lastSmoothedKeypoints = currentKeypoints
            return currentKeypoints
        }

        val smoothed = currentKeypoints.mapIndexed { index, current ->
            val previous = previousKeypoints[index]

            if (current.confidence < MIN_CONFIDENCE_TO_SMOOTH || previous.confidence < MIN_CONFIDENCE_TO_SMOOTH) {
                current
            } else {
                val dx = current.x - previous.x
                val dy = current.y - previous.y
                val movement = kotlin.math.sqrt(dx * dx + dy * dy)

                if (movement < MICRO_JITTER_THRESHOLD) {
                    previous.copy(confidence = current.confidence)
                } else {
                    val alpha = when {
                        index in ARM_KEYPOINTS -> armAdaptiveAlpha(movement)
                        else -> adaptiveAlpha(movement)
                    }
                Keypoint(
                    x = lerp(previous.x, current.x, alpha),
                    y = lerp(previous.y, current.y, alpha),
                    confidence = current.confidence
                )
                }
            }
        }

        lastSmoothedKeypoints = smoothed
        return smoothed
    }

    private fun lerp(start: Float, end: Float, alpha: Float): Float {
        return start + (end - start) * alpha
    }

    private fun emptyPoseResult(): PoseResult {
        val emptyKeypoints = List(KEYPOINT_COUNT) { Keypoint(0f, 0f, 0f) }
        return PoseResult(
            keypoints = emptyKeypoints,
            score = 0f,
            rawKeypoints = emptyKeypoints
        )
    }

    private fun adaptiveAlpha(movement: Float): Float {
        return when {
            movement < STILL_MOVEMENT_THRESHOLD -> STILL_ALPHA
            movement > FAST_MOVEMENT_THRESHOLD -> FAST_ALPHA
            else -> DEFAULT_ALPHA
        }
    }

    private fun armAdaptiveAlpha(movement: Float): Float {
        return when {
            movement < STILL_MOVEMENT_THRESHOLD -> ARM_STILL_ALPHA
            movement > FAST_MOVEMENT_THRESHOLD -> ARM_FAST_ALPHA
            else -> ARM_DEFAULT_ALPHA
        }
    }

    private val ARM_KEYPOINTS = setOf(
        Keypoint.LEFT_SHOULDER,
        Keypoint.RIGHT_SHOULDER,
        Keypoint.LEFT_ELBOW,
        Keypoint.RIGHT_ELBOW,
        Keypoint.LEFT_WRIST,
        Keypoint.RIGHT_WRIST
    )

    fun close() {
        lastSmoothedKeypoints = null
        isProcessingFrame = false
        poseDetector.close()
    }
}
