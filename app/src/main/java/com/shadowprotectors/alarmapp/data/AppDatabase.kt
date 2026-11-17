package com.shadowprotectors.alarmapp.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [SavedLocation::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {

    abstract fun savedLocationDao(): SavedLocationDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `saved_locations` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `custom_name` TEXT NOT NULL,
                        `latitude` REAL NOT NULL,
                        `longitude` REAL NOT NULL,
                        `created_at` INTEGER NOT NULL,
                        `last_used_at` INTEGER NOT NULL,
                        `use_count` INTEGER NOT NULL
                    )
                """.trimIndent())

                db.execSQL("""
                        INSERT INTO `saved_locations` (`custom_name`, `latitude`, `longitude`, `created_at`, `last_used_at`, `use_count`)
                        SELECT `name`, `latitude`, `longitude`, `created_at`, `created_at`, 1
                        FROM `destinations`
                """)
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE?.let { return@synchronized it }
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "travel_alarm.db"
                )
                .addMigrations(MIGRATION_1_2)
                .addCallback(object : RoomDatabase.Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        super.onCreate(db)
                        val now = System.currentTimeMillis()
                        val presets = listOf(
                            SavedLocation(customName = "🚆 Madurai Junction", latitude = 9.9196, longitude = 78.1100, createdAt = now, lastUsedAt = now, useCount = 1),
                            SavedLocation(customName = "🚆 Chennai Central", latitude = 13.0827, longitude = 80.2707, createdAt = now, lastUsedAt = now, useCount = 1),
                            SavedLocation(customName = "✈️ Chennai Airport", latitude = 12.9941, longitude = 80.1709, createdAt = now, lastUsedAt = now, useCount = 1),
                            SavedLocation(customName = "🚆 Coimbatore Junction", latitude = 10.9972, longitude = 76.9634, createdAt = now, lastUsedAt = now, useCount = 1),
                            SavedLocation(customName = "🚆 Bengaluru Majestic", latitude = 12.9781, longitude = 77.5696, createdAt = now, lastUsedAt = now, useCount = 1)
                        )
                        for (p in presets) {
                            db.execSQL(
                                "INSERT INTO saved_locations (custom_name, latitude, longitude, created_at, last_used_at, use_count) VALUES (?, ?, ?, ?, ?, ?)",
                                arrayOf<Any>(p.customName, p.latitude, p.longitude, p.createdAt, p.lastUsedAt, p.useCount)
                            )
                        }
                    }
                })
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
