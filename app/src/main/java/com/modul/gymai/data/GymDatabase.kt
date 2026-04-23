package com.modul.gymai.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [WorkoutSession::class],
    version = 4,
    exportSchema = false
)
abstract class GymDatabase : RoomDatabase() {

    abstract fun workoutSessionDao(): WorkoutSessionDao

    companion object {
        @Volatile
        private var INSTANCE: GymDatabase? = null

        private val MIGRATION_1_4 = object : Migration(1, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                addColumnIfMissing(db, "mostFrequentFeedback", "TEXT")
                addColumnIfMissing(db, "feedbackSummary", "TEXT")
                addColumnIfMissing(db, "evaluationVideoPath", "TEXT")
            }
        }

        private val MIGRATION_2_4 = object : Migration(2, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                addColumnIfMissing(db, "feedbackSummary", "TEXT")
                addColumnIfMissing(db, "evaluationVideoPath", "TEXT")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                addColumnIfMissing(db, "evaluationVideoPath", "TEXT")
            }
        }

        fun getInstance(context: Context): GymDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    GymDatabase::class.java,
                    "gym_database"
                )
                    .addMigrations(MIGRATION_1_4, MIGRATION_2_4, MIGRATION_3_4)
                    .build()
                INSTANCE = instance
                instance
            }
        }

        private fun addColumnIfMissing(
            db: SupportSQLiteDatabase,
            columnName: String,
            columnDefinition: String
        ) {
            val cursor = db.query("PRAGMA table_info(workout_sessions)")
            var exists = false
            while (cursor.moveToNext()) {
                val name = cursor.getString(cursor.getColumnIndexOrThrow("name"))
                if (name == columnName) {
                    exists = true
                    break
                }
            }
            cursor.close()

            if (!exists) {
                db.execSQL("ALTER TABLE workout_sessions ADD COLUMN $columnName $columnDefinition")
            }
        }
    }
}
