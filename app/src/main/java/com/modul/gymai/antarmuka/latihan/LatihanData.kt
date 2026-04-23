package com.modul.gymai.antarmuka.latihan

import com.modul.gymai.R
import com.modul.gymai.engine.ExerciseType

data class Exercise(
    val id: String,
    val name: String,
    val level: String,
    val target: String,
    val imageResId: Int,
    val type: ExerciseType,
    val description: String = "",
    val primaryMuscleGroup: String = "",
    val equipment: String = "",
    val muscleTags: List<String> = emptyList(),
    val stepCount: Int = 0,
    val firstStepBrief: String = ""
)

object ExerciseRepository {
    fun getAllExercises(): List<Exercise> {
        return listOf(
            Exercise(
                id = "1",
                name = "Squat",
                level = "Menengah",
                target = "Otot: Paha, Bokong, Betis",
                imageResId = R.drawable.squat,
                type = ExerciseType.SQUAT,
                description = "Melatih kekuatan dan massa otot tubuh bagian bawah",
                primaryMuscleGroup = "Quadriceps & Glutes",
                equipment = "Barbell / Bodyweight",
                muscleTags = listOf("Quadriceps", "Gluteus Maximus", "Hamstrings", "+3 lagi"),
                stepCount = 8,
                firstStepBrief = "Pastikan seluruh tubuh terlihat dan tubuh menghadap samping serong ke kamera"
            ),
            Exercise(
                id = "2",
                name = "Biceps Curl",
                level = "Pemula",
                target = "Otot: Biseps, Lengan Atas",
                imageResId = R.drawable.bicepcurl,
                type = ExerciseType.BICEP_CURL,
                description = "Fokus pada otot bicep dan kekuatan tarikan lengan",
                primaryMuscleGroup = "Biceps",
                equipment = "Dumbbell",
                muscleTags = listOf("Biceps Brachii", "Brachialis", "Forearms"),
                stepCount = 6,
                firstStepBrief = "Pastikan seluruh tubuh terlihat dan tubuh menghadap samping ke kamera"
            ),
            Exercise(
                id = "3",
                name = "Lateral Raise",
                level = "Pemula",
                target = "Otot: Bahu (Deltoid Lateral)",
                imageResId = R.drawable.squat,
                type = ExerciseType.LATERAL_RAISE,
                description = "Melatih otot bahu bagian samping untuk lebar bahu ideal",
                primaryMuscleGroup = "Lateral Deltoids",
                equipment = "Dumbbell",
                muscleTags = listOf("Lateral Deltoid", "Anterior Deltoid", "Trapezius"),
                stepCount = 5,
                firstStepBrief = "Pastikan seluruh tubuh terlihat dan tubuh menghadap depan ke kamera"
            ),
            Exercise(
                id = "4",
                name = "Shoulder Press",
                level = "Menengah",
                target = "Otot: Bahu, Triceps",
                imageResId = R.drawable.press,
                type = ExerciseType.SHOULDER_PRESS,
                description = "Meningkatkan kekuatan dorongan ke atas",
                primaryMuscleGroup = "Shoulders & Triceps",
                equipment = "Dumbbell / Barbell",
                muscleTags = listOf("Anterior Deltoid", "Triceps", "Upper Chest"),
                stepCount = 6,
                firstStepBrief = "Pastikan seluruh tubuh terlihat dan tubuh menghadap samping ke kamera"
            )
        )
    }
}
