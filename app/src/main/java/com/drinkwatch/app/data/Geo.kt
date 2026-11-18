package com.drinkwatch.app.data

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Location helpers. Uses LocationManager so the app works without Play Services. */
object Geo {

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Best effort position: last known fix from any provider, else a single request
     * with a short timeout. Returns null rather than blocking when nothing is available.
     */
    suspend fun current(context: Context): Pair<Double, Double>? {
        if (!hasPermission(context)) return null
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
        runCatching {
            providers.mapNotNull { provider ->
                if (manager.isProviderEnabled(provider)) manager.getLastKnownLocation(provider) else null
            }.maxByOrNull { it.time }?.let { return it.latitude to it.longitude }
        }

        return kotlinx.coroutines.withTimeoutOrNull(12_000L) {
            suspendCancellableCoroutine { continuation ->
            var resumed = false
            val listener = object : android.location.LocationListener {
                override fun onLocationChanged(location: android.location.Location) {
                    if (!resumed) {
                        resumed = true
                        runCatching { manager.removeUpdates(this) }
                        continuation.resume(location.latitude to location.longitude)
                    }
                }

                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
                override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
            }
            val provider = providers.firstOrNull { manager.isProviderEnabled(it) }
            if (provider == null) {
                continuation.resume(null)
                return@suspendCancellableCoroutine
            }
            runCatching {
                manager.requestLocationUpdates(provider, 0L, 0f, listener, android.os.Looper.getMainLooper())
            }.onFailure { continuation.resume(null) }

            continuation.invokeOnCancellation { runCatching { manager.removeUpdates(listener) } }
            }
        }
    }

    fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val radius = 6371.0088
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dPhi = p2 - p1
        val dLambda = Math.toRadians(lon2 - lon1)
        val a = sin(dPhi / 2).pow(2) + cos(p1) * cos(p2) * sin(dLambda / 2).pow(2)
        return 2 * radius * asin(sqrt(a))
    }

    fun mapsIntent(lat: Double?, lon: Double?, label: String?): Intent {
        val uri = if (lat != null && lon != null) {
            Uri.parse("geo:" + lat + "," + lon + "?q=" + lat + "," + lon + "(" + Uri.encode(label ?: "Store") + ")")
        } else {
            Uri.parse("geo:0,0?q=" + Uri.encode(label ?: ""))
        }
        return Intent(Intent.ACTION_VIEW, uri)
    }
}
