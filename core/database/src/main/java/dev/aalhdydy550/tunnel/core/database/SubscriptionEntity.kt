package dev.aalhdydy550.tunnel.core.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A saved subscription: a URL that serves a list of proxy links
 * (plain text, or base64 of the same list — the standard subscription
 * format). The app fetches it on demand and daily in the background
 * ([SubscriptionWorker][dev.aalhdydy550.tunnel.core.data.backup.SubscriptionWorker])
 * and replaces the profiles that came from it — never the user's
 * manually imported profiles.
 *
 * The URL itself is the user's own subscription (their provider's
 * link) — except for the one built-in default subscription, which
 * serves the public free-server list (see
 * [DefaultSubscription][dev.aalhdydy550.tunnel.core.data.profile.DefaultSubscription])
 * and is marked with [isDefault]. The user can change the default
 * subscription's URL from the import screen, or delete it; deleting
 * it only removes its servers, it never touches manual profiles.
 */
@Entity(tableName = "subscriptions")
data class SubscriptionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String,
    val name: String,
    /** Epoch millis of the last fetch attempt, 0 when never fetched. */
    val lastFetchedAt: Long = 0,
    /** Profiles the last successful fetch produced. */
    val lastProfileCount: Int = 0,
    /** "" when the last fetch succeeded; the error message otherwise. */
    val lastError: String = "",
    /**
     * True for the built-in default free subscription. Added in v4.
     * The SQL default mirrors MIGRATION_3_4's `DEFAULT 0`, so a
     * migrated database and a fresh v4 install have identical schemas
     * (same pattern as `countryCode` in v2).
     */
    @ColumnInfo(defaultValue = "0")
    val isDefault: Boolean = false,
)
