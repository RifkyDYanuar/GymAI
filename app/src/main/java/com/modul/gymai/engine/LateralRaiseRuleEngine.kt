package com.modul.gymai.engine

import com.modul.gymai.pose.Keypoint
import com.modul.gymai.pose.PoseResult
import com.modul.gymai.utils.AngleUtils

class LateralRaiseRuleEngine : ExerciseRuleEngine {

    companion object {
        private const val MIN_CONF = 0.45f
        private const val SHOULDER_PEAK_MIN = 70f
        private const val SHOULDER_PEAK_MAX = 100f
        private const val TORSO_STABILITY_THRESHOLD = 10f
    }

    override fun validate(pose: PoseResult?): RuleResult {
        if (pose == null || !pose.isValid()) {
            return RuleResult(false, "Pastikan seluruh tubuh terlihat kamera")
        }

        val kp = pose.keypoints
        val lShoulder = kp[Keypoint.LEFT_SHOULDER]; val rShoulder = kp[Keypoint.RIGHT_SHOULDER]
        val lElbow = kp[Keypoint.LEFT_ELBOW]; val rElbow = kp[Keypoint.RIGHT_ELBOW]
        val lHip = kp[Keypoint.LEFT_HIP]; val rHip = kp[Keypoint.RIGHT_HIP]

        val leftShoulderAngle = if (lShoulder.confidence > MIN_CONF && lElbow.confidence > MIN_CONF) {
            AngleUtils.verticalAngle(lShoulder.x, lShoulder.y, lElbow.x, lElbow.y)
        } else 0f
        val rightShoulderAngle = if (rShoulder.confidence > MIN_CONF && rElbow.confidence > MIN_CONF) {
            AngleUtils.verticalAngle(rShoulder.x, rShoulder.y, rElbow.x, rElbow.y)
        } else 0f
        
        val avgShoulderAngle = (leftShoulderAngle + rightShoulderAngle) / 
                               (if (leftShoulderAngle > 0 && rightShoulderAngle > 0) 2f else if (leftShoulderAngle > 0 || rightShoulderAngle > 0) 1f else 1f)

        val torsoAngle = if (lHip.confidence > MIN_CONF && rHip.confidence > MIN_CONF) {
            val midShoulderX = (lShoulder.x + rShoulder.x) / 2f
            val midShoulderY = (lShoulder.y + rShoulder.y) / 2f
            val midHipX = (lHip.x + rHip.x) / 2f
            val midHipY = (lHip.y + rHip.y) / 2f
            AngleUtils.verticalAngle(midHipX, midHipY, midShoulderX, midShoulderY)
        } else 0f

        val isTorsoStable = torsoAngle <= TORSO_STABILITY_THRESHOLD
        
        return if (avgShoulderAngle > 45f) { 
            if (avgShoulderAngle in SHOULDER_PEAK_MIN..SHOULDER_PEAK_MAX && isTorsoStable) {
                RuleResult(true, "Lengan terangkat setinggi bahu dan tubuh stabil", avgShoulderAngle, 0f, torsoAngle)
            } else {
                val feedback = if (!isTorsoStable) "Hindari tubuh condong" else "Angkat lengan setinggi bahu"
                RuleResult(false, feedback, avgShoulderAngle, 0f, torsoAngle)
            }
        } else {
            RuleResult(true, "Angkat lengan ke samping...", avgShoulderAngle, 0f, torsoAngle)
        }
    }

    override fun calculateMetric(pose: PoseResult): Float {
        val kp = pose.keypoints
        val lShoulder = kp[Keypoint.LEFT_SHOULDER]; val rShoulder = kp[Keypoint.RIGHT_SHOULDER]
        val lElbow = kp[Keypoint.LEFT_ELBOW]; val rElbow = kp[Keypoint.RIGHT_ELBOW]

        val left = if (lShoulder.confidence > MIN_CONF && lElbow.confidence > MIN_CONF) AngleUtils.verticalAngle(lShoulder.x, lShoulder.y, lElbow.x, lElbow.y) else 0f
        val right = if (rShoulder.confidence > MIN_CONF && rElbow.confidence > MIN_CONF) AngleUtils.verticalAngle(rShoulder.x, rShoulder.y, rElbow.x, rElbow.y) else 0f
        
        return (left + right) / (if (left > 0 && right > 0) 2f else if (left > 0 || right > 0) 1f else 1f)
    }

    override fun reset() {}
}
