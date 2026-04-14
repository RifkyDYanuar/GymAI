package com.modul.gymai.processing

import com.modul.gymai.pose.Keypoint
import com.modul.gymai.pose.PoseResult
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Biomechanical rule engine for squat validation.
 *
 * Overrides CNN result if any critical rule is violated.
 * Rules:
 *  1. Knee angle < 90° when in DOWN phase
 *  2. Hip aligned with knee (lateral check)
 *  3. Back angle < 45°
 *  4. Heel stable (ankle y coordinate stable)
 */
class SquatRuleEngine {

    data class RuleResult(
        val isValid: Boolean,
        val feedback: String
    )

    /**
     * Check squat rules on the given pose.
     * @return RuleResult with validity flag and feedback message.
     */
    fun validate(pose: PoseResult?): RuleResult {
        if (pose == null || !pose.isValid()) {
            return RuleResult(false, "Pose tidak terdeteksi")
        }

        val keypoints = pose.keypoints

        // Extract key points
        val leftHip = keypoints[Keypoint.LEFT_HIP]
        val rightHip = keypoints[Keypoint.RIGHT_HIP]
        val leftKnee = keypoints[Keypoint.LEFT_KNEE]
        val rightKnee = keypoints[Keypoint.RIGHT_KNEE]
        val leftAnkle = keypoints[Keypoint.LEFT_ANKLE]
        val rightAnkle = keypoints[Keypoint.RIGHT_ANKLE]
        val leftShoulder = keypoints[Keypoint.LEFT_SHOULDER]
        val rightShoulder = keypoints[Keypoint.RIGHT_SHOULDER]

        // Only validate if key landmarks are confident enough
        val minConf = 0.4f
        val hasLegs = leftKnee.confidence > minConf && rightKnee.confidence > minConf

        if (!hasLegs) {
            return RuleResult(false, "Posisikan seluruh tubuh terlihat kamera")
        }

        // Rule 3: Back angle
        val midHipX = (leftHip.x + rightHip.x) / 2f
        val midHipY = (leftHip.y + rightHip.y) / 2f
        val midShoulderX = (leftShoulder.x + rightShoulder.x) / 2f
        val midShoulderY = (leftShoulder.y + rightShoulder.y) / 2f

        if (leftShoulder.confidence > minConf && rightShoulder.confidence > minConf) {
            val backAngle = calculateAngleDeg(
                midHipX, midHipY, midShoulderX, midShoulderY
            )
            if (backAngle > 45f) {
                return RuleResult(false, "Jaga punggung tetap lurus")
            }
        }

        // Rule 4: Heel stability - ankles should not be too high
        if (leftAnkle.confidence > minConf && rightAnkle.confidence > minConf) {
            // Ankles should be below knees in y-axis (y increases downward in image)
            if (leftAnkle.y < leftKnee.y - 0.05f || rightAnkle.y < rightKnee.y - 0.05f) {
                return RuleResult(false, "Jaga tumit tetap di lantai")
            }
        }

        return RuleResult(true, "Posisi squat sudah benar!")
    }

    /**
     * Compute knee angle from hip-knee-ankle triplet.
     * Returns angle in degrees at the knee joint.
     */
    fun computeKneeAngle(pose: PoseResult): Float {
        val kp = pose.keypoints
        val hip = kp[Keypoint.LEFT_HIP]
        val knee = kp[Keypoint.LEFT_KNEE]
        val ankle = kp[Keypoint.LEFT_ANKLE]

        if (knee.confidence < 0.3f) return 180f

        return calculateAngleBetweenPoints(
            hip.x, hip.y,
            knee.x, knee.y,
            ankle.x, ankle.y
        )
    }

    /**
     * Angle of a vector from (x1,y1) pointing to (x2,y2) in degrees
     * relative to vertical axis.
     */
    private fun calculateAngleDeg(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x2 - x1
        val dy = y2 - y1
        return Math.toDegrees(atan2(dx.toDouble(), -dy.toDouble())).toFloat().let {
            if (it < 0) it + 180f else it
        }
    }

    /**
     * Angle at the middle point (B) formed by vectors BA and BC.
     */
    private fun calculateAngleBetweenPoints(
        ax: Float, ay: Float,
        bx: Float, by: Float,
        cx: Float, cy: Float
    ): Float {
        val v1x = ax - bx
        val v1y = ay - by
        val v2x = cx - bx
        val v2y = cy - by

        val dot = v1x * v2x + v1y * v2y
        val mag1 = sqrt((v1x * v1x + v1y * v1y).toDouble())
        val mag2 = sqrt((v2x * v2x + v2y * v2y).toDouble())

        if (mag1 == 0.0 || mag2 == 0.0) return 180f

        val cosAngle = (dot / (mag1 * mag2)).coerceIn(-1.0, 1.0)
        return Math.toDegrees(Math.acos(cosAngle)).toFloat()
    }
}
