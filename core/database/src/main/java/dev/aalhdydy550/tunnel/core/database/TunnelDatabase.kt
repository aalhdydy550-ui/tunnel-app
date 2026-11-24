package dev.aalhdydy550.tunnel.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

@Database(
    entities = [
        ProfileEntity::class,
        SubscriptionEntity::class,
        ReferenceServerEntity::class,
        ReferenceNetworkEntity::class,
    ],
    version = 4,
    exportSchema = false,
)
abstract class TunnelDatabase : RoomDatabase() {
    abstract fun profileDao(): ProfileDao
    abstract fun subscriptionDao(): SubscriptionDao
    abstract fun referenceServerDao(): ReferenceServerDao
    abstract fun referenceNetworkDao(): ReferenceNetworkDao

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

        /**
         * v3 → v4: adds the two reference-only tables (`referenceServers`,
         * `referenceNetworks` — Hamo reference data, never profiles) and
         * the `isDefault` column on `subscriptions` (1 = the built-in
         * default free subscription, 0 = a subscription the user added).
         *
         * The CREATE TABLE statements must match Room's expected schema
         * exactly (same rule as MIGRATION_2_3): column names, types,
         * NOT NULL, autoincrement primary key. The new tables start
         * empty on upgrade; [HamoReferenceSeeder] fills them on the
         * next app start (fresh installs get the same fill). The
         * `isDefault` column defaults to 0, so every subscription a
         * user added before this version stays a normal subscription.
         */
        val MIGRATION_3_4: Migration = object : Migration(3, 4) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS `referenceServers` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`host` TEXT NOT NULL, " +
                        "`tcpPort` TEXT NOT NULL, " +
                        "`sslPort` TEXT NOT NULL, " +
                        "`protocol` INTEGER NOT NULL, " +
                        "`flag` TEXT NOT NULL)",
                )
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS `referenceNetworks` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`sniHost` TEXT NOT NULL, " +
                        "`tunnelType` INTEGER NOT NULL, " +
                        "`carrierInfo` TEXT NOT NULL)",
                )
                connection.execSQL(
                    "ALTER TABLE subscriptions ADD COLUMN isDefault INTEGER NOT NULL DEFAULT 0",
                )
            }
        }
    }
}
