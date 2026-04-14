package com.modul.gymai.processing

import com.modul.gymai.pose.PoseResult

/**
 * Extracts a flat 51-dimensional feature vector from a PoseResult.
 *
 * Output format: [kp0_x, kp0_y, kp0_conf, kp1_x, kp1_y, kp1_conf, ..., kp16_x, kp16_y, kp16_conf]
 * Total: 17 keypoints × 3 = 51 features
 */
object FeatureExtractor {

    const val FEATURE_SIZE = 51  // 17 × 3

    /**
     * Convert normalized keypoints to a flat FloatArray of size 51.
     * If pose is null or invalid, returns a zero vector.
     */
    fun extract(pose: PoseResult?): FloatArray {
        val features = FloatArray(FEATURE_SIZE)
        if (pose == null || !pose.isValid()) return features

        for (i in pose.keypoints.indices) {
            val kp = pose.keypoints[i]
            features[i * 3] = kp.x
            features[i * 3 + 1] = kp.y
            features[i * 3 + 2] = kp.confidence
        }
        return features
    }
}
