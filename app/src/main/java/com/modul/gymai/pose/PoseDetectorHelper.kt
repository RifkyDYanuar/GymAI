package com.modul.gymai.pose

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
    private var poseDetector: PoseDetector

    init {
        val options = PoseDetectorOptions.Builder()
            .setDetectorMode(PoseDetectorOptions.STREAM_MODE)
            .build()
        
        poseDetector = PoseDetection.getClient(options)
    }

    @ExperimentalGetImage
    fun detect(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image ?: return
        val rotationDegrees = imageProxy.imageInfo.rotationDegrees
        val image = InputImage.fromMediaImage(mediaImage, rotationDegrees)
        
        // Since ML Kit handles rotation when provided to InputImage, 
        // the resulting landmarks are already in the oriented space.
        // We just need to normalize by the OREINTED dimensions.
        val isRotated = rotationDegrees == 90 || rotationDegrees == 270
        val orientedW = if (isRotated) mediaImage.height.toFloat() else mediaImage.width.toFloat()
        val orientedH = if (isRotated) mediaImage.width.toFloat() else mediaImage.height.toFloat()

        poseDetector.process(image)
            .addOnSuccessListener { pose ->
                val landmarks = pose.allPoseLandmarks
                if (landmarks.isEmpty()) {
                    imageProxy.close()
                    return@addOnSuccessListener
                }

                val filteredKeypoints = mutableListOf<Keypoint>()
                val mapping = listOf(0, 2, 5, 7, 8, 11, 12, 13, 14, 15, 16, 23, 24, 25, 26, 27, 28)

                var totalScore = 0f
                for (mlIndex in mapping) {
                    val landmark = pose.getPoseLandmark(mlIndex)
                    if (landmark != null) {
                        // ML Kit coordinates are already oriented to the display space
                        val normX = (landmark.position.x / orientedW).coerceIn(0f, 1f)
                        val normY = (landmark.position.y / orientedH).coerceIn(0f, 1f)
                        
                        filteredKeypoints.add(Keypoint(normX, normY, landmark.inFrameLikelihood))
                        totalScore += landmark.inFrameLikelihood
                    } else {
                        filteredKeypoints.add(Keypoint(0f, 0f, 0f))
                    }
                }

                val avgScore = if (filteredKeypoints.isNotEmpty()) totalScore / filteredKeypoints.size else 0f
                onResults(PoseResult(filteredKeypoints, avgScore))
                imageProxy.close()
            }
            .addOnFailureListener { e ->
                onError(e.message ?: "Pose detection failed")
                imageProxy.close()
            }
    }

    fun close() {
        poseDetector.close()
    }
}
