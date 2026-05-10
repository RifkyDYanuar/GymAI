package com.modul.gymai.engine

/**
 * Enum representing the 4 supported weight-training exercises.
 */
enum class ExerciseType(
    val displayName: String,
    val muscleGroup: String,
    val description: String,
    val difficulty: String,
    val cameraHint: String
) {
    SQUAT(
        displayName = "Squat",
        muscleGroup = "Otot: Paha, Bokong, Betis",
        description = "Latihan untuk kekuatan kaki dan inti tubuh. Efektif meningkatkan massa otot paha dan bokong.",
        difficulty = "Pemula",
        cameraHint = "Posisikan tubuh menghadap samping serong ke kiri atau kanan kamera"
    ),
    BICEP_CURL(
        displayName = "Biceps Curl",
        muscleGroup = "Otot: Biseps, Lengan Atas",
        description = "Latihan untuk melatih otot bisep. Gerakan menekuk siku dari lurus hingga sudut maksimal.",
        difficulty = "Pemula",
        cameraHint = "Posisikan tubuh menghadap samping kiri atau kanan kamera"
    ),
    LATERAL_RAISE(
        displayName = "Lateral Raise",
        muscleGroup = "Otot: Bahu (Deltoid Lateral)",
        description = "Latihan mengangkat lengan ke samping hingga sejajar bahu untuk melatih otot deltoid lateral.",
        difficulty = "Pemula",
        cameraHint = "Duduk atau berdiri tegak menghadap depan ke kamera"
    ),
    SHOULDER_PRESS(
        displayName = "Shoulder Press",
        muscleGroup = "Otot: Bahu, Triceps",
        description = "Latihan mendorong beban ke atas dari posisi bahu hingga lengan lurus sepenuhnya.",
        difficulty = "Menengah",
        cameraHint = "Posisikan tubuh menghadap depan ke kamera"
    );

    companion object {
        fun fromString(value: String): ExerciseType =
            values().firstOrNull { it.name == value } ?: SQUAT
    }
}
