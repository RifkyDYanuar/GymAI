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
        private const val MIN_CONF = 0.4f

        // Saat UP: Y siku harus dekat dengan Y bahu (±toleransi, koordinat ternormalisasi)
        // Y bertambah ke bawah, jadi Y siku "dekat" Y bahu berarti selisihnya kecil
        private const val SHOULDER_HEIGHT_TOLERANCE = 0.08f

        // Siku tidak boleh lurus — sudut di > maksimum ini = terlalu lurus
        private const val ELBOW_TOO_STRAIGHT_ANGLE = 175f

        // Siku tidak boleh terlalu ditekuk — < minimum ini = terlalu tekuk
        private const val ELBOW_TOO_BENT_ANGLE = 130f

        // Selisih ketinggian (Y) antara siku kiri dan kanan untuk simetri
        private const val SYMMETRY_THRESHOLD = 0.07f

        // Threshold naik bahu: bahu tidak boleh naik > ini dari posisi awal
        private const val SHOULDER_SHRUG_THRESHOLD = 0.05f
    }

    data class RuleResult(
        val isValid: Boolean,
        val feedback: String,
        val leftAbductionLevel: Float = 0f,  // 0 = bawah, 1 = sejajar bahu
        val rightAbductionLevel: Float = 0f
    )

    private var refLeftShoulderY: Float = -1f
    private var refRightShoulderY: Float = -1f
    private var isRefSet: Boolean = false

    /**
     * Validasi pose lateral raise pada frame saat ini.
     */
    fun validate(pose: PoseResult?): RuleResult {
        if (pose == null || !pose.isValid()) {
            return RuleResult(false, "Pose tidak terdeteksi — hadapkan kamera ke depan tubuh")
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

        // Minimal butuh bahu dan siku terdeteksi
        val hasLeft  = lShoulder.confidence > MIN_CONF && lElbow.confidence > MIN_CONF
        val hasRight = rShoulder.confidence > MIN_CONF && rElbow.confidence > MIN_CONF

        if (!hasLeft && !hasRight) {
            return RuleResult(false, "Pastikan kedua lengan terlihat kamera dari depan")
        }

        // Set referensi bahu saat pertama valid
        if (!isRefSet) {
            if (hasLeft)  refLeftShoulderY  = lShoulder.y
            if (hasRight) refRightShoulderY = rShoulder.y
            isRefSet = true
        }

        // Rule: Cek shrug bahu (bahu tidak boleh naik saat mengangkat)
        if (hasLeft && refLeftShoulderY > 0f) {
            val shrugDelta = refLeftShoulderY - lShoulder.y  // positif = bahu naik
            if (shrugDelta > SHOULDER_SHRUG_THRESHOLD) {
                return RuleResult(false, "Jangan angkat bahu saat mengangkat lengan (jaga bahu tetap turun)")
            }
        }
        if (hasRight && refRightShoulderY > 0f) {
            val shrugDelta = refRightShoulderY - rShoulder.y
            if (shrugDelta > SHOULDER_SHRUG_THRESHOLD) {
                return RuleResult(false, "Jangan angkat bahu saat mengangkat lengan (jaga bahu tetap turun)")
            }
        }

        // Rule: Cek sudut siku — tidak boleh terlalu lurus atau terlalu tekuk
        if (hasLeft && lWrist.confidence > MIN_CONF) {
            val leftElbowAngle = AngleUtils.angleBetween(
                lShoulder.x, lShoulder.y,
                lElbow.x, lElbow.y,
                lWrist.x, lWrist.y
            )
            if (leftElbowAngle > ELBOW_TOO_STRAIGHT_ANGLE) {
                return RuleResult(false, "Siku kiri terlalu lurus — tekuk sedikit untuk lindungi sendi")
            }
            if (leftElbowAngle < ELBOW_TOO_BENT_ANGLE) {
                return RuleResult(false, "Siku kiri terlalu tertekuk — luruskan lengan lebih banyak")
            }
        }
        if (hasRight && rWrist.confidence > MIN_CONF) {
            val rightElbowAngle = AngleUtils.angleBetween(
                rShoulder.x, rShoulder.y,
                rElbow.x, rElbow.y,
                rWrist.x, rWrist.y
            )
            if (rightElbowAngle > ELBOW_TOO_STRAIGHT_ANGLE) {
                return RuleResult(false, "Siku kanan terlalu lurus — tekuk sedikit untuk lindungi sendi")
            }
            if (rightElbowAngle < ELBOW_TOO_BENT_ANGLE) {
                return RuleResult(false, "Siku kanan terlalu tertekuk — luruskan lengan lebih banyak")
            }
        }

        // Hitung level abduksi (0.0 = bawah, 1.0 = sejajar/di atas bahu)
        // Ketika Y siku == Y bahu: diferensialnya 0 → level tinggi
        // Level = 1 - (Y_siku - Y_bahu) / jarak_bahu_pinggul
        val leftLevel = if (hasLeft) {
            val hipY = if (lHip.confidence > MIN_CONF) lHip.y else lShoulder.y + 0.3f
            val range = (hipY - lShoulder.y).coerceAtLeast(0.01f)
            (1f - (lElbow.y - lShoulder.y) / range).coerceIn(0f, 1f)
        } else 0f

        val rightLevel = if (hasRight) {
            val hipY = if (rHip.confidence > MIN_CONF) rHip.y else rShoulder.y + 0.3f
            val range = (hipY - rShoulder.y).coerceAtLeast(0.01f)
            (1f - (rElbow.y - rShoulder.y) / range).coerceIn(0f, 1f)
        } else 0f

        // Rule: Simetri kedua lengan saat fase UP
        val avgLevel = (leftLevel + rightLevel) / 2f
        if (avgLevel > 0.5f && hasLeft && hasRight) {
            val asymmetry = kotlin.math.abs(lElbow.y - rElbow.y)
            if (asymmetry > SYMMETRY_THRESHOLD) {
                return RuleResult(false, "Angkat kedua lengan setara — satu sisi lebih tinggi", leftLevel, rightLevel)
            }
        }

        return when {
            avgLevel < 0.3f -> RuleResult(true, "Angkat kedua lengan ke samping setinggi bahu", leftLevel, rightLevel)
            avgLevel > 0.75f -> RuleResult(true, "Bagus! Tahan sebentar lalu turunkan perlahan", leftLevel, rightLevel)
            else -> RuleResult(true, "Teruskan — angkat hingga setinggi bahu", leftLevel, rightLevel)
        }
    }

    /**
     * Hitung rata-rata level abduksi bahu untuk rep counter.
     * 0.0 = tangan di bawah (DOWN), 1.0 = tangan sejajar bahu (UP).
     */
    fun computeAbductionLevel(pose: PoseResult): Float {
        val kp = pose.keypoints
        val lShoulder = kp[Keypoint.LEFT_SHOULDER]
        val rShoulder = kp[Keypoint.RIGHT_SHOULDER]
        val lElbow    = kp[Keypoint.LEFT_ELBOW]
        val rElbow    = kp[Keypoint.RIGHT_ELBOW]
        val lHip      = kp[Keypoint.LEFT_HIP]
        val rHip      = kp[Keypoint.RIGHT_HIP]

        var count = 0
        var sum = 0f

        if (lShoulder.confidence > MIN_CONF && lElbow.confidence > MIN_CONF) {
            val hipY = if (lHip.confidence > MIN_CONF) lHip.y else lShoulder.y + 0.3f
            val range = (hipY - lShoulder.y).coerceAtLeast(0.01f)
            sum += (1f - (lElbow.y - lShoulder.y) / range).coerceIn(0f, 1f)
            count++
        }
        if (rShoulder.confidence > MIN_CONF && rElbow.confidence > MIN_CONF) {
            val hipY = if (rHip.confidence > MIN_CONF) rHip.y else rShoulder.y + 0.3f
            val range = (hipY - rShoulder.y).coerceAtLeast(0.01f)
            sum += (1f - (rElbow.y - rShoulder.y) / range).coerceIn(0f, 1f)
            count++
        }

        return if (count > 0) sum / count else 0f
    }

    fun reset() {
        refLeftShoulderY  = -1f
        refRightShoulderY = -1f
        isRefSet = false
    }
}
