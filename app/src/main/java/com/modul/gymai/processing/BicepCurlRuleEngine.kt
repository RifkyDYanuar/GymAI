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
        private const val MIN_CONF = 0.4f

        // Sudut siku saat DOWN: harus lebih besar dari ini (lengan hampir lurus)
        private const val ELBOW_DOWN_MIN_ANGLE = 140f

        // Sudut siku saat UP: harus lebih kecil dari ini (curl penuh)
        private const val ELBOW_UP_MAX_ANGLE = 70f

        // Toleransi pergeseran bahu ke atas (delta y, koordinat dinormalisasi 0-1)
        // Semakin kecil y = semakin ke atas di layar (y bertambah ke bawah)
        // Jika bahu naik, yBahu mengecil → kita cek perubahan tidak > threshold
        private const val SHOULDER_SWING_THRESHOLD = 0.06f

        // Toleransi pergeseran siku horizontal (x) — cegah siku maju terlalu jauh
        private const val ELBOW_SWING_THRESHOLD = 0.10f
    }

    data class RuleResult(
        val isValid: Boolean,
        val feedback: String,
        val elbowAngle: Float = 0f
    )

    // Referensi posisi bahu dan siku saat mulai (untuk cek stabilitas)
    private var refShoulderY: Float = -1f
    private var refElbowX: Float = -1f
    private var isRefSet: Boolean = false

    /**
     * Validasi pose bicep curl pada frame saat ini.
     */
    fun validate(pose: PoseResult?): RuleResult {
        if (pose == null || !pose.isValid()) {
            return RuleResult(false, "Pose tidak terdeteksi — posisikan tubuh dari samping")
        }

        val kp = pose.keypoints

        // Ambil keypoints sisi kanan (lebih visible dari kamera samping kanan)
        // Fallback ke sisi kiri jika sisi kanan tidak confident
        val (shoulder, elbow, wrist) = pickBestArm(kp) ?: run {
            return RuleResult(false, "Pastikan lengan terlihat jelas dari samping")
        }

        // Hitung sudut siku: Shoulder → Elbow → Wrist
        val elbowAngle = AngleUtils.angleBetween(
            shoulder.x, shoulder.y,
            elbow.x, elbow.y,
            wrist.x, wrist.y
        )

        // Set referensi saat pertama kali terbaca valid
        if (!isRefSet) {
            refShoulderY = shoulder.y
            refElbowX = elbow.x
            isRefSet = true
        }

        // Rule 1: Cek elevasi bahu (tidak boleh berayun ke atas)
        val shoulderDeltaY = refShoulderY - shoulder.y // positif = bahu naik
        if (shoulderDeltaY > SHOULDER_SWING_THRESHOLD) {
            return RuleResult(false, "Jaga bahu tetap diam, jangan diangkat saat mengangkat beban", elbowAngle)
        }

        // Rule 2: Cek pergeseran siku horizontal (siku tidak boleh maju berlebihan)
        val elbowDeltaX = kotlin.math.abs(elbow.x - refElbowX)
        if (elbowDeltaX > ELBOW_SWING_THRESHOLD) {
            return RuleResult(false, "Jaga siku tetap di sisi tubuh, jangan diayunkan ke depan", elbowAngle)
        }

        // Rule 3: Feedback posisi berdasarkan fase
        return when {
            elbowAngle > ELBOW_DOWN_MIN_ANGLE -> {
                // Fase DOWN — siap angkat
                RuleResult(true, "Siap: tekuk siku dan angkat beban", elbowAngle)
            }
            elbowAngle < ELBOW_UP_MAX_ANGLE -> {
                // Fase UP — sudah curl penuh
                RuleResult(true, "Bagus! Kembali turunkan perlahan", elbowAngle)
            }
            else -> {
                // Fase tengah — sedang bergerak
                RuleResult(true, "Teruskan gerakan curl dengan kontrol", elbowAngle)
            }
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

    /** Reset referensi posisi (panggil saat mulai sesi baru) */
    fun reset() {
        refShoulderY = -1f
        refElbowX = -1f
        isRefSet = false
    }
}
