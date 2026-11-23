package dev.aalhdydy550.tunnel.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [ProfileEntity::class], version = 2, exportSchema = false)
abstract class TunnelDatabase : RoomDatabase() {
    abstract fun profileDao(): ProfileDao

    companion object {
        /**
         * v1 → v2: adds the `countryCode` column (flag emoji per server).
         * A real migration — never a destructive fallback — because the
         * profile table holds the user's servers.
         */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE profiles ADD COLUMN countryCode TEXT NOT NULL DEFAULT ''",
                )
            }
        }
    }
}
