package com.modul.gymai.processing

import com.modul.gymai.pose.Keypoint
import com.modul.gymai.pose.PoseResult
import com.modul.gymai.utils.AngleUtils

/**
 * Biomechanical rule engine for Lateral Raise validation.
 *
 * == Camera Angle ==
 * Kamera dari arah DEPAN (frontal view). Sudut depan memberikan
 * visibilitas terbaik untuk:
 *   - Melihat kedua lengan terangkat simetris ke samping
 *   - Memastikan level ketinggian kedua tangan sejajar bahu
 *
 * == Keypoints yang digunakan ==
 *   LEFT_SHOULDER → LEFT_ELBOW   (sudut abduksi kiri)
 *   RIGHT_SHOULDER → RIGHT_ELBOW (sudut abduksi kanan)
 *   Kedua sisi divalidasi untuk simetri
 *
 * == Konsep sudut abduksi bahu ==
 *   Abduksi diukur dari posisi lengan vertikal (menggantung = 0°/180°)
 *   hingga horizontal (sejajar bahu = 90°).
 *   Dari kamera depan: kita bandingkan posisi Y siku vs Y bahu.
 *   Jika Y siku ≈ Y bahu → siku sejajar bahu → abduksi ~90°.
 *
 * == Aturan Evaluasi ==
 *   1. Saat UP: kedua siku harus terangkat mendekati ketinggian bahu
 *      (Y siku ≈ Y bahu ± toleransi)
 *   2. Siku sedikit ditekuk — tidak boleh lurus sempurna (cedera)
 *      Sudut siku harus antara 150°–175° (sedikit tekuk)
 *   3. Kedua lengan simetris — selisih ketinggian siku ≤ threshold
 *   4. Tidak menaikkan bahu (shrug) saat mengangkat
 */
class LateralRaiseRuleEngine {

    companion object {
        private const val MIN_CONF = 0.45f
        
        // Peaks based on rule-based data provided by user
        private const val SHOULDER_PEAK_MIN = 70f
        private const val SHOULDER_PEAK_MAX = 100f
        
        private const val TORSO_STABILITY_THRESHOLD = 10f // Torso deviation <= 10
    }

    data class RuleResult(
        val isValid: Boolean,
        val feedback: String,
        val shoulderAngle: Float = 0f,
        val torsoAngle: Float = 0f
    )

    /**
     * Validasi pose lateral raise pada frame saat ini.
     */
    fun validate(pose: PoseResult?): RuleResult {
        if (pose == null || !pose.isValid()) {
            return RuleResult(false, "Pastikan seluruh tubuh terlihat kamera")
        }

        val kp = pose.keypoints

        val lShoulder = kp[Keypoint.LEFT_SHOULDER]
        val rShoulder = kp[Keypoint.RIGHT_SHOULDER]
        val lElbow    = kp[Keypoint.LEFT_ELBOW]
        val rElbow    = kp[Keypoint.RIGHT_ELBOW]
        val lHip      = kp[Keypoint.LEFT_HIP]
        val rHip      = kp[Keypoint.RIGHT_HIP]

        // 1. Calculate Shoulder Abduction Angle (Upper Arm vs Vertical)
        val leftShoulderAngle = if (lShoulder.confidence > MIN_CONF && lElbow.confidence > MIN_CONF) {
            AngleUtils.verticalAngle(lShoulder.x, lShoulder.y, lElbow.x, lElbow.y)
        } else 0f
        val rightShoulderAngle = if (rShoulder.confidence > MIN_CONF && rElbow.confidence > MIN_CONF) {
            AngleUtils.verticalAngle(rShoulder.x, rShoulder.y, rElbow.x, rElbow.y)
        } else 0f
        
        val avgShoulderAngle = (leftShoulderAngle + rightShoulderAngle) / 
                              (if (leftShoulderAngle > 0 && rightShoulderAngle > 0) 2f else 1f)

        // 2. Calculate Torso Deviation
        val torsoAngle = if (lHip.confidence > MIN_CONF && rHip.confidence > MIN_CONF) {
            val midShoulderX = (lShoulder.x + rShoulder.x) / 2f
            val midShoulderY = (lShoulder.y + rShoulder.y) / 2f
            val midHipX = (lHip.x + rHip.x) / 2f
            val midHipY = (lHip.y + rHip.y) / 2f
            AngleUtils.verticalAngle(midHipX, midHipY, midShoulderX, midShoulderY)
        } else 0f

        val isTorsoStable = torsoAngle <= TORSO_STABILITY_THRESHOLD
        
        // Evaluate based on peaks
        return if (avgShoulderAngle > 45f) { // In upward phase
            if (avgShoulderAngle in SHOULDER_PEAK_MIN..SHOULDER_PEAK_MAX && isTorsoStable) {
                RuleResult(true, "Lengan terangkat setinggi bahu dan tubuh stabil", avgShoulderAngle, torsoAngle)
            } else {
                val feedback = if (!isTorsoStable) "Hindari tubuh condong"
                              else "Angkat lengan setinggi bahu"
                RuleResult(false, feedback, avgShoulderAngle, torsoAngle)
            }
        } else {
            RuleResult(true, "Angkat lengan ke samping...", avgShoulderAngle, torsoAngle)
        }
    }

    /**
     * Hitung rata-rata sudut abduksi bahu untuk rep counter.
     * Menggunakan sudut humeral (lengan atas) terhadap vertikal.
     */
    fun computeShoulderAngle(pose: PoseResult): Float {
        val kp = pose.keypoints
        val lShoulder = kp[Keypoint.LEFT_SHOULDER]
        val rShoulder = kp[Keypoint.RIGHT_SHOULDER]
        val lElbow    = kp[Keypoint.LEFT_ELBOW]
        val rElbow    = kp[Keypoint.RIGHT_ELBOW]

        val leftShoulderAngle = if (lShoulder.confidence > MIN_CONF && lElbow.confidence > MIN_CONF) {
            AngleUtils.verticalAngle(lShoulder.x, lShoulder.y, lElbow.x, lElbow.y)
        } else 0f
        val rightShoulderAngle = if (rShoulder.confidence > MIN_CONF && rElbow.confidence > MIN_CONF) {
            AngleUtils.verticalAngle(rShoulder.x, rShoulder.y, rElbow.x, rElbow.y)
        } else 0f
        
        return (leftShoulderAngle + rightShoulderAngle) / 
              (if (leftShoulderAngle > 0 && rightShoulderAngle > 0) 2f else if (leftShoulderAngle > 0 || rightShoulderAngle > 0) 1f else 1f)
    }

    fun reset() {
    }
}
