package com.fitledger.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object Migrations {
    const val DATABASE_VERSION = 8
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // No table recreation, no UPDATE of existing nutritional or historical values.
            db.execSQL("ALTER TABLE foods ADD COLUMN energyInputUnit TEXT NOT NULL DEFAULT 'KCAL'")
            db.execSQL("CREATE TABLE IF NOT EXISTS pending_settings (id INTEGER NOT NULL, payload TEXT NOT NULL, PRIMARY KEY(id))")
        }
    }
    val MIGRATION_2_3=object:Migration(2,3) {
        override fun migrate(db:SupportSQLiteDatabase) {
            // No historical energy estimates are invented. Every new column is nullable.
            db.execSQL("ALTER TABLE bodies ADD COLUMN energy_age INTEGER")
            db.execSQL("ALTER TABLE bodies ADD COLUMN energy_heightCm REAL")
            db.execSQL("ALTER TABLE bodies ADD COLUMN energy_sex TEXT")
            db.execSQL("ALTER TABLE bodies ADD COLUMN energy_birthday INTEGER")
            db.execSQL("ALTER TABLE bodies ADD COLUMN energy_activityFactor REAL")
            db.execSQL("ALTER TABLE bodies ADD COLUMN energy_bmrKcal REAL")
            db.execSQL("ALTER TABLE bodies ADD COLUMN energy_expenditureKcal REAL")
        }
    }
    val MIGRATION_3_4=object:Migration(3,4) {
        override fun migrate(db:SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS exercises (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, day INTEGER NOT NULL, type TEXT NOT NULL, minutes REAL, met REAL, weight REAL, kcal REAL NOT NULL, included INTEGER NOT NULL)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_exercises_day ON exercises (day)")
        }
    }
    val MIGRATION_4_5=object:Migration(4,5) {
        override fun migrate(db:SupportSQLiteDatabase) {
            // All existing foods remain available; no snapshots or original food data change.
            db.execSQL("ALTER TABLE foods ADD COLUMN isDeleted INTEGER NOT NULL DEFAULT 0")
        }
    }
    val MIGRATION_5_6=object:Migration(5,6) {
        override fun migrate(db:SupportSQLiteDatabase) {
            // SQLite cannot remove NOT NULL in place. Room runs this copy in its upgrade
            // transaction; every historical value, including muscle and energy, is copied exactly.
            db.execSQL("CREATE TABLE bodies_optional_muscle (day INTEGER NOT NULL, weight REAL NOT NULL, fatPercent REAL NOT NULL, lean REAL NOT NULL, muscle REAL, energy_age INTEGER, energy_heightCm REAL, energy_sex TEXT, energy_birthday INTEGER, energy_activityFactor REAL, energy_bmrKcal REAL, energy_expenditureKcal REAL, PRIMARY KEY(day))")
            val columns="day,weight,fatPercent,lean,muscle,energy_age,energy_heightCm,energy_sex,energy_birthday,energy_activityFactor,energy_bmrKcal,energy_expenditureKcal"
            db.execSQL("INSERT INTO bodies_optional_muscle ($columns) SELECT $columns FROM bodies")
            db.execSQL("DROP TABLE bodies")
            db.execSQL("ALTER TABLE bodies_optional_muscle RENAME TO bodies")
            // Direct macro records have no food identity. Preserve food references and row IDs
            // from every previous intake, and label them as FOOD without recalculating nutrition.
            db.execSQL("CREATE TABLE intakes_with_source (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, day INTEGER NOT NULL, foodId INTEGER, name TEXT NOT NULL, unit TEXT NOT NULL, quantity REAL NOT NULL, kcal REAL NOT NULL, carbs REAL NOT NULL, protein REAL NOT NULL, fat REAL NOT NULL, source TEXT NOT NULL DEFAULT 'FOOD')")
            db.execSQL("INSERT INTO intakes_with_source (id,day,foodId,name,unit,quantity,kcal,carbs,protein,fat) SELECT id,day,foodId,name,unit,quantity,kcal,carbs,protein,fat FROM intakes")
            // Keep the old AUTOINCREMENT high-water mark, including IDs whose rows were deleted.
            db.execSQL("UPDATE sqlite_sequence SET seq = MAX(seq, COALESCE((SELECT seq FROM sqlite_sequence WHERE name='intakes'), 0)) WHERE name='intakes_with_source'")
            db.execSQL("INSERT INTO sqlite_sequence (name,seq) SELECT 'intakes_with_source',seq FROM sqlite_sequence WHERE name='intakes' AND NOT EXISTS (SELECT 1 FROM sqlite_sequence WHERE name='intakes_with_source')")
            db.execSQL("DROP TABLE intakes")
            db.execSQL("ALTER TABLE intakes_with_source RENAME TO intakes")
            db.execSQL("CREATE INDEX index_intakes_day ON intakes (day)")
        }
    }
    val MIGRATION_6_7=object:Migration(6,7) {
        override fun migrate(db:SupportSQLiteDatabase) {
            // New organizational settings only; measurements and intake/target snapshots stay untouched.
            db.execSQL("CREATE TABLE IF NOT EXISTS food_zones (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, dailyMaximum REAL, recommendationMaximum REAL, sortOrder INTEGER NOT NULL DEFAULT 0)")
            db.execSQL("ALTER TABLE foods ADD COLUMN zoneId INTEGER")
            db.execSQL("ALTER TABLE foods ADD COLUMN zonePortionSize REAL NOT NULL DEFAULT 1.0")
            db.execSQL("ALTER TABLE foods ADD COLUMN sortOrder INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE foods ADD COLUMN nutritionTags TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE foods ADD COLUMN pricePerUnitYuan REAL")
            db.execSQL("ALTER TABLE intakes ADD COLUMN costYuan REAL")
            db.execSQL("CREATE TABLE IF NOT EXISTS daily_food_templates (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, foodId INTEGER NOT NULL, unit TEXT NOT NULL, mode TEXT NOT NULL, quantity REAL NOT NULL, enabled INTEGER NOT NULL, startDay INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE IF NOT EXISTS daily_food_applications (day INTEGER NOT NULL, templateId INTEGER NOT NULL, intakeId INTEGER NOT NULL, PRIMARY KEY(day,templateId))")
            db.execSQL("CREATE UNIQUE INDEX index_daily_food_applications_intakeId ON daily_food_applications (intakeId)")
            db.execSQL("CREATE TABLE IF NOT EXISTS day_modes (day INTEGER NOT NULL, isIndulgence INTEGER NOT NULL, PRIMARY KEY(day))")
        }
    }
    val MIGRATION_7_8=object:Migration(7,8) {
        override fun migrate(db:SupportSQLiteDatabase) {
            // Existing limits remain portions; no amounts or historical snapshots are converted.
            db.execSQL("ALTER TABLE food_zones ADD COLUMN limitUnit TEXT NOT NULL DEFAULT 'PORTION'")
            db.execSQL("ALTER TABLE foods ADD COLUMN gramsPerPortion REAL")
        }
    }
    val ALL = arrayOf(MIGRATION_1_2,MIGRATION_2_3,MIGRATION_3_4,MIGRATION_4_5,MIGRATION_5_6,MIGRATION_6_7,MIGRATION_7_8)
}

