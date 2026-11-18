package com.drinkwatch.app.data

import android.content.Context
import android.location.Geocoder
import java.util.Locale

/** Suburb/town name for a coordinate, so a blank location label is still useful. */
object PlaceName {

    /** Blocking: call from Dispatchers.IO. Returns null when geocoding is unavailable. */
    fun resolve(context: Context, lat: Double, lon: Double): String? = runCatching {
        if (!Geocoder.isPresent()) return null
        val geocoder = Geocoder(context, Locale.getDefault())
        @Suppress("DEPRECATION")
        val address = geocoder.getFromLocation(lat, lon, 1)?.firstOrNull() ?: return null
        listOfNotNull(
            address.locality,
            address.subAdminArea,
            address.subLocality,
            address.adminArea,
        ).firstOrNull { it.isNotBlank() }
    }.getOrNull()
}
