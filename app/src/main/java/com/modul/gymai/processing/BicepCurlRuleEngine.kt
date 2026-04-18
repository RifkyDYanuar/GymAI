package com.modul.gymai.processing

import com.modul.gymai.pose.Keypoint
import com.modul.gymai.pose.PoseResult
import com.modul.gymai.utils.AngleUtils

/**
 * Biomechanical rule engine for Bicep Curl validation.
 *
 * == Camera Angle ==
 * Kamera harus memotret dari SAMPING (profil lateral) tubuh pengguna.
 * Sudut samping memberikan visibilitas optimal terhadap fleksi siku,
 * sehingga perubahan sudut lengan atas → siku → lengan bawah dapat
 * dianalisis secara akurat.
 *
 * == Keypoints yang digunakan (sisi kanan sebagai acuan utama) ==
 *   RIGHT_SHOULDER → RIGHT_ELBOW → RIGHT_WRIST  (angle fleksi siku)
 *   RIGHT_SHOULDER → elevasi bahu
 *
 * == Fase Gerakan ==
 *   DOWN (istirahat) : siku hampir lurus → sudut siku > 150°
 *   UP   (kontraksi) : siku ditekuk penuh → sudut siku < 60°
 *
 * == Aturan Evaluasi ==
 *   1. Siku harus ditekuk minimal hingga < 60° saat fase UP
 *   2. Bahu tidak boleh terangkat/berayun (y bahu stabil)
 *   3. Siku tidak bergerak maju-mundur berlebihan (x siku stabil)
 *   4. Bahu dan siku harus terdeteksi dengan confidence cukup
 */
class BicepCurlRuleEngine {

    companion object {
        private const val MIN_CONF = 0.45f
        
        // Peaks based on rule-based data provided by user
        private const val ELBOW_PEAK_MIN = 35f
        private const val ELBOW_PEAK_MAX = 65f
        private const val ELBOW_PEAK_LIMIT = 70f // Condition Salah if > 70
        
        private const val TORSO_STABILITY_THRESHOLD = 10f // Torso deviation <= 10
    }

    data class RuleResult(
        val isValid: Boolean,
        val feedback: String,
        val elbowAngle: Float = 0f,
        val torsoAngle: Float = 0f
    )

    /**
     * Validasi pose bicep curl pada frame saat ini.
     */
    fun validate(pose: PoseResult?): RuleResult {
        if (pose == null || !pose.isValid()) {
            return RuleResult(false, "Pastikan tubuh terlihat jelas di kamera")
        }

        val kp = pose.keypoints
        val lShoulder = kp[Keypoint.LEFT_SHOULDER]
        val rShoulder = kp[Keypoint.RIGHT_SHOULDER]

        // ORIENTATION CHECK: Bicep Curl must be from the side.
        // If both shoulders are highly visible and separated, it's a front view.
        val shoulderDist = Math.abs(lShoulder.x - rShoulder.x)
        if (lShoulder.confidence > 0.5f && rShoulder.confidence > 0.5f && shoulderDist > 0.15f) {
            return RuleResult(false, "Harus menghadap ke samping", 0f, 0f)
        }

        // Bicep curl usually tracked from side, but can be front. 
        // We pick the best arm.
        val (shoulder, elbow, wrist) = pickBestArm(kp) ?: run {
            return RuleResult(false, "Lengan tidak terdeteksi")
        }

        // 1. Calculate Elbow Angle
        val elbowAngle = AngleUtils.angleBetween(
            shoulder.x, shoulder.y,
            elbow.x, elbow.y,
            wrist.x, wrist.y
        )

        // 2. Calculate Torso Deviation (Shoulder to Hip angle relative to vertical)
        val lHip = kp[Keypoint.LEFT_HIP]
        val rHip = kp[Keypoint.RIGHT_HIP]
        val torsoAngle = if (lHip.confidence > MIN_CONF && rHip.confidence > MIN_CONF) {
            val midShoulderX = (kp[Keypoint.LEFT_SHOULDER].x + kp[Keypoint.RIGHT_SHOULDER].x) / 2f
            val midShoulderY = (kp[Keypoint.LEFT_SHOULDER].y + kp[Keypoint.RIGHT_SHOULDER].y) / 2f
            val midHipX = (lHip.x + rHip.x) / 2f
            val midHipY = (lHip.y + rHip.y) / 2f
            AngleUtils.verticalAngle(midHipX, midHipY, midShoulderX, midShoulderY)
        } else 0f

        // Validation Logic
        val isTorsoStable = torsoAngle <= TORSO_STABILITY_THRESHOLD
        
        // We evaluate based on the "puncak" (contraction) phase
        // If the user is in the contraction phase but doesn't reach the goal:
        return if (elbowAngle < 90f) { // In contraction phase
             if (elbowAngle in ELBOW_PEAK_MIN..ELBOW_PEAK_MAX && isTorsoStable) {
                 RuleResult(true, "Fleksi siku optimal dan tubuh stabil", elbowAngle, torsoAngle)
             } else {
                 val feedback = if (!isTorsoStable) "Jaga tubuh tetap tegak" 
                               else "Angkat beban lebih tinggi"
                 RuleResult(false, feedback, elbowAngle, torsoAngle)
             }
        } else {
            // Extension phase or starting
            RuleResult(true, "Lakukan gerakan curl...", elbowAngle, torsoAngle)
        }
    }

    /**
     * Hitung sudut siku untuk keperluan rep counter.
     * Menggunakan sisi terbaik yang terdeteksi.
     */
    fun computeElbowAngle(pose: PoseResult): Float {
        val kp = pose.keypoints
        val (shoulder, elbow, wrist) = pickBestArm(kp) ?: return 180f
        return AngleUtils.angleBetween(
            shoulder.x, shoulder.y,
            elbow.x, elbow.y,
            wrist.x, wrist.y
        )
    }

    /**
     * Pilih sisi lengan terbaik (kanan/kiri) berdasarkan confidence.
     * Dari kamera samping, biasanya hanya satu sisi yang terlihat jelas.
     */
    private fun pickBestArm(kp: List<com.modul.gymai.pose.Keypoint>): Triple<com.modul.gymai.pose.Keypoint, com.modul.gymai.pose.Keypoint, com.modul.gymai.pose.Keypoint>? {
        val rShoulder = kp[Keypoint.RIGHT_SHOULDER]
        val rElbow    = kp[Keypoint.RIGHT_ELBOW]
        val rWrist    = kp[Keypoint.RIGHT_WRIST]
        val lShoulder = kp[Keypoint.LEFT_SHOULDER]
        val lElbow    = kp[Keypoint.LEFT_ELBOW]
        val lWrist    = kp[Keypoint.LEFT_WRIST]

        val rightScore = rShoulder.confidence + rElbow.confidence + rWrist.confidence
        val leftScore  = lShoulder.confidence + lElbow.confidence + lWrist.confidence

        return when {
            rightScore >= leftScore && rElbow.confidence > MIN_CONF ->
                Triple(rShoulder, rElbow, rWrist)
            lElbow.confidence > MIN_CONF ->
                Triple(lShoulder, lElbow, lWrist)
            else -> null
        }
    }

    /** Reset state (none for this rulebased approach) */
    fun reset() {
    }
}
