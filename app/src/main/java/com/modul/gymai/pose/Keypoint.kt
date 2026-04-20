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
        // Standard COCO 17-keypoint mapping for YOLOv8-Pose
        const val NOSE = 0
        const val LEFT_EYE = 1
        const val RIGHT_EYE = 2
        const val LEFT_EAR = 3
        const val RIGHT_EAR = 4
        const val LEFT_SHOULDER = 5
        const val RIGHT_SHOULDER = 6
        const val LEFT_ELBOW = 7
        const val RIGHT_ELBOW = 8
        const val LEFT_WRIST = 9
        const val RIGHT_WRIST = 10
        const val LEFT_HIP = 11
        const val RIGHT_HIP = 12
        const val LEFT_KNEE = 13
        const val RIGHT_KNEE = 14
        const val LEFT_ANKLE = 15
        const val RIGHT_ANKLE = 16

        // Aliases for skeletal joints
        const val LEFT_FOOT = LEFT_ANKLE
        const val RIGHT_FOOT = RIGHT_ANKLE
        const val LEFT_HAND = LEFT_WRIST
        const val RIGHT_HAND = RIGHT_WRIST

        val SKELETON_CONNECTIONS = listOf(
            // Face
            Pair(LEFT_EYE, NOSE), Pair(RIGHT_EYE, NOSE),
            Pair(LEFT_EAR, LEFT_EYE), Pair(RIGHT_EAR, RIGHT_EYE),
            
            // Torso
            Pair(LEFT_SHOULDER, RIGHT_SHOULDER),
            Pair(LEFT_SHOULDER, LEFT_HIP),
            Pair(RIGHT_SHOULDER, RIGHT_HIP),
            Pair(LEFT_HIP, RIGHT_HIP),
            
            // Arms
            Pair(LEFT_SHOULDER, LEFT_ELBOW),
            Pair(LEFT_ELBOW, LEFT_WRIST),
            Pair(RIGHT_SHOULDER, RIGHT_ELBOW),
            Pair(RIGHT_ELBOW, RIGHT_WRIST),
            
            // Legs
            Pair(LEFT_HIP, LEFT_KNEE),
            Pair(LEFT_KNEE, LEFT_ANKLE),
            Pair(RIGHT_HIP, RIGHT_KNEE),
            Pair(RIGHT_KNEE, RIGHT_ANKLE)
        )
    }
}
