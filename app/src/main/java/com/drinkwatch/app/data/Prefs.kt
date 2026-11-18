package com.drinkwatch.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Device settings and the crowd-submission token.
 *
 * The token lives in EncryptedSharedPreferences; if the keystore is unavailable
 * (rare, mostly broken vendor ROMs) we fall back to private prefs so the app keeps
 * working rather than crashing on launch.
 */
class Prefs(context: Context) {

    private val appContext = context.applicationContext
    private val store: SharedPreferences = open()

    private fun open(): SharedPreferences = runCatching {
        val key = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            appContext,
            "drinkwatch_secure",
            key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        ) as SharedPreferences
    }.getOrElse {
        appContext.getSharedPreferences("drinkwatch_fallback", Context.MODE_PRIVATE)
    }

    var token: String?
        get() = store.getString(KEY_TOKEN, null)
        set(value) = store.edit().putString(KEY_TOKEN, value).apply()

    var deviceId: Int
        get() = store.getInt(KEY_DEVICE_ID, 0)
        set(value) = store.edit().putInt(KEY_DEVICE_ID, value).apply()

    var displayName: String
        get() = store.getString(KEY_NAME, "") ?: ""
        set(value) = store.edit().putString(KEY_NAME, value).apply()

    var baseUrl: String
        get() = store.getString(KEY_BASE_URL, DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL
        set(value) = store.edit().putString(KEY_BASE_URL, value.trimEnd('/')).apply()

    var lat: Double
        get() = store.getString(KEY_LAT, null)?.toDoubleOrNull() ?: DEFAULT_LAT
        set(value) = store.edit().putString(KEY_LAT, value.toString()).apply()

    var lon: Double
        get() = store.getString(KEY_LON, null)?.toDoubleOrNull() ?: DEFAULT_LON
        set(value) = store.edit().putString(KEY_LON, value.toString()).apply()

    var homeLabel: String
        get() = store.getString(KEY_HOME_LABEL, "") ?: ""
        set(value) = store.edit().putString(KEY_HOME_LABEL, value).apply()

    /** Suburb/town resolved from the last location fix; used when no label is set. */
    var placeLabel: String
        get() = store.getString(KEY_PLACE_LABEL, "") ?: ""
        set(value) = store.edit().putString(KEY_PLACE_LABEL, value).apply()

    /** What the home screen should call this place. */
    fun locationTitle(): String =
        homeLabel.ifBlank { placeLabel }.ifBlank { "My location" }

    /** Store a fresh fix; only fills the label when the user has not named it. */
    fun applyPosition(lat: Double, lon: Double, place: String?) {
        this.lat = lat
        this.lon = lon
        if (place.isNullOrBlank()) return
        if (homeLabel.isBlank()) placeLabel = place
    }

    var radiusKm: Int
        get() = store.getInt(KEY_RADIUS, 15)
        set(value) = store.edit().putInt(KEY_RADIUS, value.coerceIn(1, 100)).apply()

    /** Slugs shown on the home screen and in the widget; empty = server default list. */
    var trackedSlugs: List<String>
        get() = (store.getString(KEY_TRACKED, "") ?: "")
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        set(value) = store.edit().putString(KEY_TRACKED, value.joinToString(",")).apply()

    var basketRaw: String
        get() = store.getString(KEY_BASKET, "") ?: ""
        set(value) = store.edit().putString(KEY_BASKET, value).apply()

    var widgetCache: String?
        get() = store.getString(KEY_WIDGET_CACHE, null)
        set(value) = store.edit().putString(KEY_WIDGET_CACHE, value).apply()

    var widgetCacheAt: Long
        get() = store.getLong(KEY_WIDGET_AT, 0L)
        set(value) = store.edit().putLong(KEY_WIDGET_AT, value).apply()

    var lastSeenNotificationId: Int
        get() = store.getInt(KEY_LAST_NOTIFICATION, 0)
        set(value) = store.edit().putInt(KEY_LAST_NOTIFICATION, value).apply()

    fun addTracked(slug: String) {
        if (slug.isBlank()) return
        val current = trackedSlugs
        if (slug !in current) trackedSlugs = current + slug
    }

    fun removeTracked(slug: String) {
        trackedSlugs = trackedSlugs.filterNot { it == slug }
    }

    fun hasLocation(): Boolean = store.getString(KEY_LAT, null) != null

    companion object {
        const val DEFAULT_BASE_URL = "https://cans.hazzardous.online"
        const val DEFAULT_LAT = -31.9523
        const val DEFAULT_LON = 115.8613
        private const val KEY_TOKEN = "token"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_NAME = "display_name"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_LAT = "lat"
        private const val KEY_LON = "lon"
        private const val KEY_HOME_LABEL = "home_label"
        private const val KEY_RADIUS = "radius_km"
        private const val KEY_TRACKED = "tracked"
        private const val KEY_WIDGET_CACHE = "widget_cache"
        private const val KEY_WIDGET_AT = "widget_cache_at"
        private const val KEY_LAST_NOTIFICATION = "last_notification_id"
        private const val KEY_BASKET = "basket"
        private const val KEY_PLACE_LABEL = "place_label"
    }
}
