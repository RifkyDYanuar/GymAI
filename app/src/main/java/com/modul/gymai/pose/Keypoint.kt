package com.modul.gymai.pose

/**
 * Represents a single body keypoint detected by YOLO-Pose.
 * Updated to match the custom 3D keypoint mapping provided by user.
 */
data class Keypoint(
    val x: Float,       // Normalized [0,1] relative to image width
    val y: Float,       // Normalized [0,1] relative to image height
    val confidence: Float
) {
    companion object {
        // Custom 17-keypoint mapping from image
        const val BOTTOM_TORSO = 0
        const val LEFT_HIP = 1
        const val LEFT_KNEE = 2
        const val LEFT_FOOT = 3  // Ankle/Foot
        const val RIGHT_HIP = 4
        const val RIGHT_KNEE = 5
        const val RIGHT_FOOT = 6 // Ankle/Foot
        const val CENTER_TORSO = 7
        const val UPPER_TORSO = 8
        const val NECK_BASE = 9
        const val CENTER_HEAD = 10
        const val RIGHT_SHOULDER = 11
        const val RIGHT_ELBOW = 12
        const val RIGHT_HAND = 13 // Wrist/Hand
        const val LEFT_SHOULDER = 14
        const val LEFT_ELBOW = 15
        const val LEFT_HAND = 16 // Wrist/Hand

        // Aliases for compatibility with legacy rule names if needed
        const val LEFT_ANKLE = LEFT_FOOT
        const val RIGHT_ANKLE = RIGHT_FOOT
        const val LEFT_WRIST = LEFT_HAND
        const val RIGHT_WRIST = RIGHT_HAND

        val SKELETON_CONNECTIONS = listOf(
            // Spine & Head
            Pair(CENTER_HEAD, NECK_BASE),
            Pair(NECK_BASE, UPPER_TORSO),
            Pair(UPPER_TORSO, CENTER_TORSO),
            Pair(CENTER_TORSO, BOTTOM_TORSO),
            
            // Shoulders to Spine
            Pair(LEFT_SHOULDER, UPPER_TORSO),
            Pair(RIGHT_SHOULDER, UPPER_TORSO),
            
            // Left arm
            Pair(LEFT_SHOULDER, LEFT_ELBOW),
            Pair(LEFT_ELBOW, LEFT_HAND),
            
            // Right arm
            Pair(RIGHT_SHOULDER, RIGHT_ELBOW),
            Pair(RIGHT_ELBOW, RIGHT_HAND),
            
            // Hips to Spine
            Pair(LEFT_HIP, BOTTOM_TORSO),
            Pair(RIGHT_HIP, BOTTOM_TORSO),
            
            // Left leg
            Pair(LEFT_HIP, LEFT_KNEE),
            Pair(LEFT_KNEE, LEFT_FOOT),
            
            // Right leg
            Pair(RIGHT_HIP, RIGHT_KNEE),
            Pair(RIGHT_KNEE, RIGHT_FOOT)
        )
    }
}

/**
 * Full pose result containing 17 keypoints.
 */
data class PoseResult(
    val keypoints: List<Keypoint>,
    val score: Float = 1f
) {
    fun isValid(): Boolean = keypoints.size >= 17

    fun getKeypoint(index: Int): Keypoint? = keypoints.getOrNull(index)
}
