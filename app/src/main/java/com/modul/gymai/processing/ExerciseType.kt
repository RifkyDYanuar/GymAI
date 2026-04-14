package com.modul.gymai.processing

/**
 * Enum representing the 4 supported weight-training exercises.
 * Each entry carries display metadata and the CNN model asset filename.
 */
enum class ExerciseType(
    val displayName: String,
    val modelFile: String,
    val emoji: String,
    val muscleGroup: String,
    val description: String,
    val difficulty: String,
    val cameraHint: String
) {
    SQUAT(
        displayName   = "Squat",
        modelFile     = "squat_classifier.tflite",
        emoji         = "🏋",
        muscleGroup   = "Otot: Paha, Bokong, Betis",
        description   = "Latihan untuk kekuatan kaki dan inti tubuh. Efektif meningkatkan massa otot paha dan bokong.",
        difficulty    = "Pemula",
        cameraHint    = "Hadapkan kamera ke arah depan tubuh"
    ),
    BICEP_CURL(
        displayName   = "Bicep Curl",
        modelFile     = "bicep_curl_classifier.tflite",
        emoji         = "💪",
        muscleGroup   = "Otot: Biseps, Lengan Atas",
        description   = "Latihan untuk melatih otot bisep. Gerakan menekuk siku dari lurus hingga sudut maksimal.",
        difficulty    = "Pemula",
        cameraHint    = "Posisikan kamera dari arah samping tubuh"
    ),
    LATERAL_RAISE(
        displayName   = "Lateral Raise",
        modelFile     = "lateral_raise_classifier.tflite",
        emoji         = "🙆",
        muscleGroup   = "Otot: Bahu (Deltoid Lateral)",
        description   = "Latihan mengangkat lengan ke samping hingga sejajar bahu untuk melatih otot deltoid lateral.",
        difficulty    = "Pemula",
        cameraHint    = "Hadapkan kamera ke arah depan tubuh"
    ),
    SHOULDER_PRESS(
        displayName   = "Shoulder Press",
        modelFile     = "shoulder_press_classifier.tflite",
        emoji         = "🏅",
        muscleGroup   = "Otot: Bahu, Triceps",
        description   = "Latihan mendorong beban ke atas dari posisi bahu hingga lengan lurus sepenuhnya.",
        difficulty    = "Menengah",
        cameraHint    = "Hadapkan kamera ke arah depan tubuh"
    );

    companion object {
        fun fromString(value: String): ExerciseType =
            values().firstOrNull { it.name == value } ?: SQUAT
    }
}
