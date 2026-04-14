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
        private const val MIN_CONF = 0.4f

        // DOWN phase: siku harus dalam rentang ini untuk posisi awal yang benar
        private const val ELBOW_DOWN_MIN = 75f
        private const val ELBOW_DOWN_MAX = 115f

        // UP phase: siku harus > ini (lengan hampir lurus ke atas)
        private const val ELBOW_UP_MIN = 155f

        // Simetri: selisih sudut siku kiri-kanan tidak boleh lebih dari ini
        private const val SYMMETRY_THRESHOLD_DEG = 20f

        // Cek punggung: selisih X antara pinggul dan bahu (dari depan sangat terbatas)
        // Kita cek apakah posisi Y pinggul tidak berubah ekstrem (proxy untuk lordosis)
        private const val BACK_ARCH_THRESHOLD = 0.05f
    }

    data class RuleResult(
        val isValid: Boolean,
        val feedback: String,
        val leftElbowAngle: Float = 0f,
        val rightElbowAngle: Float = 0f
    )

    private var refHipY: Float = -1f
    private var isRefSet: Boolean = false

    /**
     * Validasi pose shoulder press pada frame saat ini.
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

        val hasLeft  = lShoulder.confidence > MIN_CONF &&
                       lElbow.confidence > MIN_CONF &&
                       lWrist.confidence > MIN_CONF
        val hasRight = rShoulder.confidence > MIN_CONF &&
                       rElbow.confidence > MIN_CONF &&
                       rWrist.confidence > MIN_CONF

        if (!hasLeft && !hasRight) {
            return RuleResult(false, "Pastikan kedua lengan terlihat kamera dari depan")
        }

        // Hitung sudut siku masing-masing sisi
        val leftElbowAngle = if (hasLeft) {
            AngleUtils.angleBetween(
                lShoulder.x, lShoulder.y,
                lElbow.x, lElbow.y,
                lWrist.x, lWrist.y
            )
        } else 0f

        val rightElbowAngle = if (hasRight) {
            AngleUtils.angleBetween(
                rShoulder.x, rShoulder.y,
                rElbow.x, rElbow.y,
                rWrist.x, rWrist.y
            )
        } else 0f

        // Set referensi Y pinggul untuk deteksi lordosis
        val hasHips = lHip.confidence > MIN_CONF && rHip.confidence > MIN_CONF
        if (!isRefSet && hasHips) {
            refHipY = (lHip.y + rHip.y) / 2f
            isRefSet = true
        }

        // Rule: Cek punggung melengkung (lordosis proxy via pinggul naik atau depan)
        if (hasHips && refHipY > 0f) {
            val midHipY = (lHip.y + rHip.y) / 2f
            val hipDelta = refHipY - midHipY // positif = pinggul naik (maju saat press)
            if (hipDelta > BACK_ARCH_THRESHOLD) {
                return RuleResult(false, "Jaga punggung lurus — jangan melengkung ke belakang",
                    leftElbowAngle, rightElbowAngle)
            }
        }

        // Tentukan fase berdasarkan rata-rata sudut siku
        val avgElbow = listOfNotNull(
            if (hasLeft) leftElbowAngle else null,
            if (hasRight) rightElbowAngle else null
        ).average().toFloat()

        // Rule: Cek simetri kedua lengan
        if (hasLeft && hasRight) {
            val asymmetry = kotlin.math.abs(leftElbowAngle - rightElbowAngle)
            if (asymmetry > SYMMETRY_THRESHOLD_DEG && avgElbow < ELBOW_UP_MIN) {
                return RuleResult(false, "Angkat kedua lengan secara merata — posisi tidak simetris",
                    leftElbowAngle, rightElbowAngle)
            }
        }

        // Rule: Validasi posisi DOWN (fase awal press — siku di bahu)
        if (avgElbow < ELBOW_DOWN_MIN) {
            return RuleResult(false, "Siku terlalu tertekuk — mulai dari posisi siku ~90°",
                leftElbowAngle, rightElbowAngle)
        }

        // Feedback berdasarkan fase
        return when {
            avgElbow in ELBOW_DOWN_MIN..ELBOW_DOWN_MAX -> {
                RuleResult(true, "Posisi awal benar — dorong beban ke atas", leftElbowAngle, rightElbowAngle)
            }
            avgElbow >= ELBOW_UP_MIN -> {
                RuleResult(true, "Bagus! Tahan sebentar lalu turunkan perlahan", leftElbowAngle, rightElbowAngle)
            }
            else -> {
                RuleResult(true, "Teruskan dorongan ke atas dengan kontrol", leftElbowAngle, rightElbowAngle)
            }
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
        refHipY = -1f
        isRefSet = false
    }
}
