package dev.aalhdydy550.tunnel.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

@Database(
    entities = [ProfileEntity::class, SubscriptionEntity::class],
    version = 3,
    exportSchema = false,
)
abstract class TunnelDatabase : RoomDatabase() {
    abstract fun profileDao(): ProfileDao
    abstract fun subscriptionDao(): SubscriptionDao

    companion object {
        /**
         * v1 → v2: adds the `countryCode` column (flag emoji per server).
         * A real migration — never a destructive fallback — because the
         * profile table holds the user's servers.
         */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "ALTER TABLE profiles ADD COLUMN countryCode TEXT NOT NULL DEFAULT ''",
                )
            }
        }

        /**
         * v2 → v3: adds the `subscriptions` table and the nullable
         * `subscriptionId` column on `profiles` (null = manually
         * imported). The column is nullable so existing rows need no
         * backfill, and a subscription refresh deletes only rows with
         * its own id. The CREATE TABLE statement must match Room's
         * expected schema exactly (column names, types, NOT NULL,
         * autoincrement primary key) or Room rejects the migrated
         * database on open.
         */
        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS `subscriptions` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`url` TEXT NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`lastFetchedAt` INTEGER NOT NULL, " +
                        "`lastProfileCount` INTEGER NOT NULL, " +
                        "`lastError` TEXT NOT NULL)",
                )
                connection.execSQL(
                    "ALTER TABLE profiles ADD COLUMN subscriptionId INTEGER",
                )
            }
        }
    }
}
