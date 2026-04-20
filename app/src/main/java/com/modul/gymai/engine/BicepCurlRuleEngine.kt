package com.modul.gymai.engine

import com.modul.gymai.pose.Keypoint
import com.modul.gymai.pose.PoseResult
import com.modul.gymai.utils.AngleUtils

class BicepCurlRuleEngine : ExerciseRuleEngine {

    companion object {
        private const val MIN_CONF = 0.45f
        private const val ELBOW_PEAK_MIN = 35f
        private const val ELBOW_PEAK_MAX = 65f
        private const val TORSO_STABILITY_THRESHOLD = 10f
    }

    override fun validate(pose: PoseResult?): RuleResult {
        if (pose == null || !pose.isValid()) {
            return RuleResult(false, "Pastikan tubuh terlihat jelas di kamera")
        }

        val kp = pose.keypoints
        val lShoulder = kp[Keypoint.LEFT_SHOULDER]
        val rShoulder = kp[Keypoint.RIGHT_SHOULDER]

        val shoulderDist = Math.abs(lShoulder.x - rShoulder.x)
        if (lShoulder.confidence > 0.5f && rShoulder.confidence > 0.5f && shoulderDist > 0.15f) {
            return RuleResult(false, "Harus menghadap ke samping", 0f, 0f)
        }

        val (shoulder, elbow, wrist) = pickBestArm(kp) ?: run {
            return RuleResult(false, "Lengan tidak terdeteksi")
        }

        val elbowAngle = AngleUtils.angleBetween(shoulder.x, shoulder.y, elbow.x, elbow.y, wrist.x, wrist.y)

        val lHip = kp[Keypoint.LEFT_HIP]; val rHip = kp[Keypoint.RIGHT_HIP]
        val torsoAngle = if (lHip.confidence > MIN_CONF && rHip.confidence > MIN_CONF) {
            val midShoulderX = (kp[Keypoint.LEFT_SHOULDER].x + kp[Keypoint.RIGHT_SHOULDER].x) / 2f
            val midShoulderY = (kp[Keypoint.LEFT_SHOULDER].y + kp[Keypoint.RIGHT_SHOULDER].y) / 2f
            val midHipX = (lHip.x + rHip.x) / 2f
            val midHipY = (lHip.y + rHip.y) / 2f
            AngleUtils.verticalAngle(midHipX, midHipY, midShoulderX, midShoulderY)
        } else 0f

        val isTorsoStable = torsoAngle <= TORSO_STABILITY_THRESHOLD
        
        return if (elbowAngle < 90f) {
             if (elbowAngle in ELBOW_PEAK_MIN..ELBOW_PEAK_MAX && isTorsoStable) {
                 RuleResult(true, "Fleksi siku optimal dan tubuh stabil", elbowAngle, 0f, torsoAngle)
             } else {
                 val feedback = if (!isTorsoStable) "Jaga tubuh tetap tegak" else "Angkat beban lebih tinggi"
                 RuleResult(false, feedback, elbowAngle, 0f, torsoAngle)
             }
        } else {
            RuleResult(true, "Lakukan gerakan curl...", elbowAngle, 0f, torsoAngle)
        }
    }

    override fun calculateMetric(pose: PoseResult): Float {
        val kp = pose.keypoints
        val (shoulder, elbow, wrist) = pickBestArm(kp) ?: return 180f
        return AngleUtils.angleBetween(shoulder.x, shoulder.y, elbow.x, elbow.y, wrist.x, wrist.y)
    }

    private fun pickBestArm(kp: List<com.modul.gymai.pose.Keypoint>): Triple<com.modul.gymai.pose.Keypoint, com.modul.gymai.pose.Keypoint, com.modul.gymai.pose.Keypoint>? {
        val rShoulder = kp[Keypoint.RIGHT_SHOULDER]; val rElbow = kp[Keypoint.RIGHT_ELBOW]; val rWrist = kp[Keypoint.RIGHT_WRIST]
        val lShoulder = kp[Keypoint.LEFT_SHOULDER]; val lElbow = kp[Keypoint.LEFT_ELBOW]; val lWrist = kp[Keypoint.LEFT_WRIST]

        val rightScore = rShoulder.confidence + rElbow.confidence + rWrist.confidence
        val leftScore  = lShoulder.confidence + lElbow.confidence + lWrist.confidence

        return when {
            rightScore >= leftScore && rElbow.confidence > MIN_CONF -> Triple(rShoulder, rElbow, rWrist)
            lElbow.confidence > MIN_CONF -> Triple(lShoulder, lElbow, lWrist)
            else -> null
        }
    }

    override fun reset() {}
}
