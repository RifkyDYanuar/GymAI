package com.modul.gymai.engine

import com.modul.gymai.pose.Keypoint
import com.modul.gymai.pose.PoseResult
import com.modul.gymai.utils.AngleUtils

class SquatRuleEngine : ExerciseRuleEngine {

    companion object {
        private const val MIN_CONF = 0.45f
        private const val KNEE_PEAK_MIN = 80f
        private const val KNEE_PEAK_MAX = 110f
        private const val HIP_PEAK_MIN = 70f
        private const val HIP_PEAK_MAX = 110f
        private const val TORSO_STABILITY_THRESHOLD = 20f
    }

    override fun validate(pose: PoseResult?): RuleResult {
        if (pose == null || !pose.isValid()) {
            return RuleResult(false, "Pastikan seluruh tubuh terlihat kamera")
        }

        val kp = pose.rawKeypoints
        val lHip = kp[Keypoint.LEFT_HIP]; val rHip = kp[Keypoint.RIGHT_HIP]
        val lKnee = kp[Keypoint.LEFT_KNEE]; val rKnee = kp[Keypoint.RIGHT_KNEE]
        val lAnkle = kp[Keypoint.LEFT_ANKLE]; val rAnkle = kp[Keypoint.RIGHT_ANKLE]
        val lShoulder = kp[Keypoint.LEFT_SHOULDER]; val rShoulder = kp[Keypoint.RIGHT_SHOULDER]

        val leftKneeAngle = if (lHip.confidence > MIN_CONF && lKnee.confidence > MIN_CONF && lAnkle.confidence > MIN_CONF) {
            AngleUtils.angleBetween(lHip.x, lHip.y, lKnee.x, lKnee.y, lAnkle.x, lAnkle.y)
        } else 0f
        val rightKneeAngle = if (rHip.confidence > MIN_CONF && rKnee.confidence > MIN_CONF && rAnkle.confidence > MIN_CONF) {
            AngleUtils.angleBetween(rHip.x, rHip.y, rKnee.x, rKnee.y, rAnkle.x, rAnkle.y)
        } else 0f
        val avgKneeAngle = if (leftKneeAngle > 0 && rightKneeAngle > 0) (leftKneeAngle + rightKneeAngle) / 2f 
                          else if (leftKneeAngle > 0) leftKneeAngle else rightKneeAngle

        val leftHipAngle = if (lShoulder.confidence > MIN_CONF && lHip.confidence > MIN_CONF && lKnee.confidence > MIN_CONF) {
            AngleUtils.angleBetween(lShoulder.x, lShoulder.y, lHip.x, lHip.y, lKnee.x, lKnee.y)
        } else 0f
        val rightHipAngle = if (rShoulder.confidence > MIN_CONF && rHip.confidence > MIN_CONF && rKnee.confidence > MIN_CONF) {
            AngleUtils.angleBetween(rShoulder.x, rShoulder.y, rHip.x, rHip.y, rKnee.x, rKnee.y)
        } else 0f
        val avgHipAngle = if (leftHipAngle > 0 && rightHipAngle > 0) (leftHipAngle + rightHipAngle) / 2f
                         else if (leftHipAngle > 0) leftHipAngle else rightHipAngle

        val midShoulderX = (lShoulder.x + rShoulder.x) / 2f
        val midShoulderY = (lShoulder.y + rShoulder.y) / 2f
        val torsoAngle = if (lHip.confidence > MIN_CONF && rHip.confidence > MIN_CONF) {
            val midHipX = (lHip.x + rHip.x) / 2f
            val midHipY = (lHip.y + rHip.y) / 2f
            AngleUtils.verticalAngle(midHipX, midHipY, midShoulderX, midShoulderY)
        } else 0f

        val isTorsoStable = torsoAngle <= TORSO_STABILITY_THRESHOLD

        return if (avgKneeAngle < 140f) {
            val isKneeCorrect = avgKneeAngle in KNEE_PEAK_MIN..KNEE_PEAK_MAX
            val isHipCorrect = avgHipAngle in HIP_PEAK_MIN..HIP_PEAK_MAX
            
            if (isKneeCorrect && isHipCorrect && isTorsoStable) {
                RuleResult(true, "Kedalaman squat cukup dan postur terkontrol", avgKneeAngle, avgHipAngle, torsoAngle)
            } else {
                val feedback = "Turunkan pinggul lebih dalam dan jaga tubuh tetap stabil"
                RuleResult(false, feedback, avgKneeAngle, avgHipAngle, torsoAngle)
            }
        } else {
            RuleResult(true, "Turunkan pinggul Anda...", avgKneeAngle, avgHipAngle, torsoAngle)
        }
    }

    override fun calculateMetric(pose: PoseResult): Float {
        val kp = pose.rawKeypoints
        val lHip = kp[Keypoint.LEFT_HIP]; val lKnee = kp[Keypoint.LEFT_KNEE]; val lAnkle = kp[Keypoint.LEFT_ANKLE]
        val rHip = kp[Keypoint.RIGHT_HIP]; val rKnee = kp[Keypoint.RIGHT_KNEE]; val rAnkle = kp[Keypoint.RIGHT_ANKLE]
        
        val left = if (lKnee.confidence > 0.4f) AngleUtils.angleBetween(lHip.x, lHip.y, lKnee.x, lKnee.y, lAnkle.x, lAnkle.y) else 180f
        val right = if (rKnee.confidence > 0.4f) AngleUtils.angleBetween(rHip.x, rHip.y, rKnee.x, rKnee.y, rAnkle.x, rAnkle.y) else 180f
        return (left + right) / 2f
    }

    override fun reset() {}
}
