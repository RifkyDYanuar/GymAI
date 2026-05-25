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
) : IPoseDetector {
    companion object {
        private const val KEYPOINT_COUNT = 17
    }

    private var poseDetector: PoseDetector
    @Volatile private var isProcessingFrame = false

    init {
        val options = PoseDetectorOptions.Builder()
            .setDetectorMode(PoseDetectorOptions.STREAM_MODE)
            .setPreferredHardwareConfigs(PoseDetectorOptions.CPU_GPU)
            .build()
        poseDetector = PoseDetection.getClient(options)
    }

    @ExperimentalGetImage
    override fun detect(imageProxy: ImageProxy) {
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

    override fun detectBitmap(bitmap: Bitmap, recycleAfterUse: Boolean) {
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
                    val emptyKeypoints = List(KEYPOINT_COUNT) { Keypoint(0f, 0f, 0f) }
                    val emptyResult = PoseResult(keypoints = emptyKeypoints, score = 0f, rawKeypoints = emptyKeypoints)
                    isProcessingFrame = false
                    onFinished()
                    onResults(emptyResult)
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
              
                val result = PoseResult(
                    keypoints = filteredKeypoints,
                    score = avgScore,
                    rawKeypoints = filteredKeypoints,
                    sourceWidth = orientedWidth,
                    sourceHeight = orientedHeight
                )
                isProcessingFrame = false
                onFinished()
                onResults(result)
            }
            .addOnFailureListener { e ->
                isProcessingFrame = false
                onError(e.message ?: "Pose detection failed")
                onFinished()
            }
    }

    override fun isBusy(): Boolean = isProcessingFrame

    override fun close() {
        isProcessingFrame = false
        poseDetector.close()
    }
}
