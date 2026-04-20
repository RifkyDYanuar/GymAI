package com.modul.gymai.pose

/** Data class representing the result of a pose detection. */
data class PoseResult(val keypoints: List<Keypoint>, val score: Float = 1f) {
    /** Check if the pose is valid (has enough confidence or minimum detected points). */
    fun isValid(): Boolean {
        // Very strict threshold: 9 points must be clear and avg score must be high
        return keypoints.count { it.confidence > 0.6f } >= 9 && score > 0.4f
    }
}
