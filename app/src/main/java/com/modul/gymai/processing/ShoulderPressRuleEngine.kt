package com.modul.gymai.processing

import com.modul.gymai.pose.Keypoint
import com.modul.gymai.pose.PoseResult
import com.modul.gymai.utils.AngleUtils

/**
 * Biomechanical rule engine for Shoulder Press validation.
 *
 * == Camera Angle ==
 * Kamera dari arah DEPAN (frontal view). Sudut depan memberikan
 * visibilitas terbaik untuk:
 *   - Melihat kedua lengan saat mendorong ke atas secara simetris
 *   - Memastikan siku pada posisi ~90° saat DOWN (posisi awal)
 *   - Memastikan lengan lurus saat UP (ekstensi penuh)
 *
 * == Fase Gerakan ==
 *   DOWN (awal) : Siku ditekuk ±90°, lengan bawah vertikal, siku sejajar bahu
 *   UP  (akhir) : Lengan lurus ke atas, sudut siku > 160°
 *
 * == Keypoints yang digunakan ==
 *   LEFT_SHOULDER  → LEFT_ELBOW  → LEFT_WRIST   (sudut siku kiri)
 *   RIGHT_SHOULDER → RIGHT_ELBOW → RIGHT_WRIST  (sudut siku kanan)
 *   LEFT_SHOULDER  → RIGHT_SHOULDER              (lebar bahu / referensi)
 *
 * == Aturan Evaluasi ==
 *   1. Saat DOWN: sudut siku harus ~80–105° (posisi awal benar)
 *   2. Saat UP: sudut siku harus > 160° (lengan hampir lurus)
 *   3. Kedua lengan simetris — selisih sudut siku ≤ threshold
 *   4. Punggung tidak melengkung ke belakang berlebihan (lordosis)
 *      → cek: pinggul tidak maju melampaui bahu secara signifikan
 */
class ShoulderPressRuleEngine {

    companion object {
        private const val MIN_CONF = 0.45f

        // Peaks based on rule-based data provided by user
        private const val ELBOW_PEAK_MIN = 160f
        private const val ELBOW_PEAK_MAX = 180f
        
        private const val ARM_TORSO_PEAK_MIN = 150f
        private const val ARM_TORSO_PEAK_MAX = 180f
        
        private const val TORSO_STABILITY_THRESHOLD = 10f
    }

    data class RuleResult(
        val isValid: Boolean,
        val feedback: String,
        val elbowAngle: Float = 0f,
        val armAngle: Float = 0f,
        val torsoAngle: Float = 0f
    )

    /**
     * Validasi pose shoulder press pada frame saat ini.
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
        val lWrist    = kp[Keypoint.LEFT_WRIST]
        val rWrist    = kp[Keypoint.RIGHT_WRIST]
        val lHip      = kp[Keypoint.LEFT_HIP]
        val rHip      = kp[Keypoint.RIGHT_HIP]

        // 1. Calculate Average Elbow Angle
        val leftElbowAngle = if (lShoulder.confidence > MIN_CONF && lElbow.confidence > MIN_CONF && lWrist.confidence > MIN_CONF) {
            AngleUtils.angleBetween(lShoulder.x, lShoulder.y, lElbow.x, lElbow.y, lWrist.x, lWrist.y)
        } else 0f
        val rightElbowAngle = if (rShoulder.confidence > MIN_CONF && rElbow.confidence > MIN_CONF && rWrist.confidence > MIN_CONF) {
            AngleUtils.angleBetween(rShoulder.x, rShoulder.y, rElbow.x, rElbow.y, rWrist.x, rWrist.y)
        } else 0f
        val avgElbowAngle = (leftElbowAngle + rightElbowAngle) / 
                           (if (leftElbowAngle > 0 && rightElbowAngle > 0) 2f else if (leftElbowAngle > 0 || rightElbowAngle > 0) 1f else 1f)

        // 2. Calculate Arm Angle to Vertical (Shoulder to Wrist)
        val leftArmAngle = if (lShoulder.confidence > MIN_CONF && lWrist.confidence > MIN_CONF) {
            AngleUtils.verticalAngle(lShoulder.x, lShoulder.y, lWrist.x, lWrist.y)
        } else 0f
        val rightArmAngle = if (rShoulder.confidence > MIN_CONF && rWrist.confidence > MIN_CONF) {
            AngleUtils.verticalAngle(rShoulder.x, rShoulder.y, rWrist.x, rWrist.y)
        } else 0f
        val avgArmAngle = (leftArmAngle + rightArmAngle) / 
                         (if (leftArmAngle > 0 && rightArmAngle > 0) 2f else if (leftArmAngle > 0 || rightArmAngle > 0) 1f else 1f)

        // 3. Calculate Torso Deviation
        val midShoulderX = (lShoulder.x + rShoulder.x) / 2f
        val midShoulderY = (lShoulder.y + rShoulder.y) / 2f
        val torsoAngle = if (lHip.confidence > MIN_CONF && rHip.confidence > MIN_CONF) {
            val midHipX = (lHip.x + rHip.x) / 2f
            val midHipY = (lHip.y + rHip.y) / 2f
            AngleUtils.verticalAngle(midHipX, midHipY, midShoulderX, midShoulderY)
        } else 0f

        val isTorsoStable = torsoAngle <= TORSO_STABILITY_THRESHOLD
        
        // Evaluate based on peak (arms pushed up)
        return if (avgElbowAngle > 130f) {
            val isElbowCorrect = avgElbowAngle in ELBOW_PEAK_MIN..ELBOW_PEAK_MAX
            val isArmCorrect = avgArmAngle in ARM_TORSO_PEAK_MIN..ARM_TORSO_PEAK_MAX
            
            if (isElbowCorrect && isArmCorrect && isTorsoStable) {
                RuleResult(true, "Dorongan hampir lurus ke atas dan postur stabil", avgElbowAngle, avgArmAngle, torsoAngle)
            } else {
                val feedback = if (!isTorsoStable) "Jaga punggung tetap stabil"
                              else "Dorong beban sampai hampir lurus"
                RuleResult(false, feedback, avgElbowAngle, avgArmAngle, torsoAngle)
            }
        } else {
            RuleResult(true, "Dorong beban ke atas...", avgElbowAngle, avgArmAngle, torsoAngle)
        }
    }

    /**
     * Hitung rata-rata sudut siku untuk rep counter.
     * DOWN = sudut kecil (~90°), UP = sudut besar (>160°).
     */
    fun computeAvgElbowAngle(pose: PoseResult): Float {
        val kp = pose.keypoints
        val lShoulder = kp[Keypoint.LEFT_SHOULDER]
        val rShoulder = kp[Keypoint.RIGHT_SHOULDER]
        val lElbow    = kp[Keypoint.LEFT_ELBOW]
        val rElbow    = kp[Keypoint.RIGHT_ELBOW]
        val lWrist    = kp[Keypoint.LEFT_WRIST]
        val rWrist    = kp[Keypoint.RIGHT_WRIST]

        var count = 0
        var sum = 0f
        if (lShoulder.confidence > MIN_CONF && lElbow.confidence > MIN_CONF && lWrist.confidence > MIN_CONF) {
            sum += AngleUtils.angleBetween(lShoulder.x, lShoulder.y, lElbow.x, lElbow.y, lWrist.x, lWrist.y)
            count++
        }
        if (rShoulder.confidence > MIN_CONF && rElbow.confidence > MIN_CONF && rWrist.confidence > MIN_CONF) {
            sum += AngleUtils.angleBetween(rShoulder.x, rShoulder.y, rElbow.x, rElbow.y, rWrist.x, rWrist.y)
            count++
        }
        return if (count > 0) sum / count else 90f
    }

    fun reset() {
    }
}
