package com.guardian.parent.service.collectors

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.guardian.parent.core.CollectorIds
import com.guardian.parent.core.Config
import com.guardian.parent.core.Logger
import com.guardian.parent.core.TimeFmt
import com.guardian.parent.data.CollectedItem
import com.guardian.parent.data.ItemKind
import com.guardian.parent.data.LocationPayload
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.function.Consumer
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Reads the current position.
 *
 * Google Play services is used when it is available on the device, otherwise
 * the app falls back to the plain platform [LocationManager]. Both paths are
 * best-effort: if no permission was granted or no provider is enabled the
 * collector simply returns nothing instead of crashing the service.
 */
class LocationCollector(
    private val context: Context,
    private val highAccuracy: Boolean = false
) : Collector {

    override val id: String = CollectorIds.LOCATION

    override suspend fun collect(): List<CollectedItem> = withContext(Dispatchers.IO) {
        try {
            val location = obtainLocation() ?: run {
                Logger.d("No location fix available")
                return@withContext emptyList()
            }

            val payload = LocationPayload(
                lat = location.latitude,
                lng = location.longitude,
                accuracy = if (location.hasAccuracy()) location.accuracy else 0f,
                speed = if (location.hasSpeed()) location.speed else 0f,
                altitude = if (location.hasAltitude()) location.altitude else 0.0,
                bearing = if (location.hasBearing()) location.bearing else 0f,
                provider = location.provider ?: "unknown",
                battery = batteryLevel(),
                at = if (location.time > 0L) location.time else System.currentTimeMillis()
            )
            Logger.d("Location fix at ${TimeFmt.iso(payload.at)} provider=${payload.provider}")
            listOf(CollectedItem(ItemKind.LOCATION, payload = payload.toJson()))
        } catch (t: Throwable) {
            Logger.e("Location collection failed", t)
            emptyList()
        }
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    private suspend fun obtainLocation(): Location? {
        val fused = fusedLocation()
        if (fused != null) return fused
        return systemLocation()
    }

    @SuppressLint("MissingPermission")
    private suspend fun fusedLocation(): Location? {
        if (!playServicesAvailable()) return null
        val client = try {
            LocationServices.getFusedLocationProviderClient(context)
        } catch (t: Throwable) {
            Logger.w("Fused location provider is unavailable", t)
            return null
        }

        // A fresh fix is preferred, but the collector never blocks for long:
        // the last known position is a perfectly good fallback.
        val fresh = try {
            withTimeoutOrNull(FIX_TIMEOUT_MS) { requestFreshFix(client) }
        } catch (t: Throwable) {
            Logger.w("Fresh location request failed", t)
            null
        }
        if (fresh != null) return fresh

        return try {
            client.lastLocationCompat()
        } catch (t: Throwable) {
            Logger.w("Unable to read the last fused location", t)
            null
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun requestFreshFix(client: FusedLocationProviderClient): Location? {
        val intervalMs = FIX_INTERVAL_MS
        val priority = if (highAccuracy) {
            Priority.PRIORITY_HIGH_ACCURACY
        } else {
            Priority.PRIORITY_BALANCED_POWER_ACCURACY
        }
        val request = LocationRequest.Builder(priority, intervalMs)
            .setMinUpdateIntervalMillis(intervalMs / 2)
            .setWaitForAccurateLocation(false)
            .build()

        return suspendCancellableCoroutine<Location?> { continuation ->
            val callback = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    try {
                        client.removeLocationUpdates(this)
                    } catch (t: Throwable) {
                        Log.w(Config.TAG, "Unable to stop the location updates", t)
                    }
                    if (continuation.isActive) continuation.resume(result.lastLocation)
                }
            }
            try {
                client.requestLocationUpdates(request, callback, Looper.getMainLooper())
            } catch (t: Throwable) {
                if (continuation.isActive) continuation.resumeWithException(t)
            }
            continuation.invokeOnCancellation {
                try {
                    client.removeLocationUpdates(callback)
                } catch (t: Throwable) {
                    Log.w(Config.TAG, "Unable to stop the location updates", t)
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun systemLocation(): Location? {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return null

        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { provider ->
                try {
                    manager.isProviderEnabled(provider)
                } catch (t: Throwable) {
                    false
                }
            }

        val lastKnown = providers
            .mapNotNull { provider ->
                try {
                    manager.getLastKnownLocation(provider)
                } catch (t: Throwable) {
                    null
                }
            }
            .maxByOrNull { it.time }

        val fresh = withTimeoutOrNull(FIX_TIMEOUT_MS) {
            for (provider in providers) {
                val fix = try {
                    requestSystemFix(manager, provider)
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    null
                }
                if (fix != null) return@withTimeoutOrNull fix
            }
            null
        }

        // Prefer the fresh fix, but fall back to the cached position when the
        // provider did not answer in time.
        return fresh ?: lastKnown
    }

    @Suppress("DEPRECATION")
    private suspend fun requestSystemFix(manager: LocationManager, provider: String): Location? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return suspendCancellableCoroutine<Location?> { continuation ->
                val consumer = Consumer<Location> { location ->
                    if (continuation.isActive) continuation.resume(location)
                }
                try {
                    manager.getCurrentLocation(
                        provider,
                        null,
                        ContextCompat.getMainExecutor(context),
                        consumer
                    )
                } catch (t: Throwable) {
                    if (continuation.isActive) continuation.resumeWithException(t)
                }
            }
        }

        return suspendCancellableCoroutine<Location?> { continuation ->
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    try {
                        manager.removeUpdates(this)
                    } catch (t: Throwable) {
                        Log.w(Config.TAG, "Unable to remove the location updates", t)
                    }
                    if (continuation.isActive) continuation.resume(location)
                }

                override fun onProviderDisabled(provider: String) = Unit
                override fun onProviderEnabled(provider: String) = Unit

                @Deprecated("Deprecated in Java")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
            }
            try {
                manager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
            } catch (t: Throwable) {
                if (continuation.isActive) continuation.resumeWithException(t)
            }
        }
    }

    private fun playServicesAvailable(): Boolean = try {
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) ==
            ConnectionResult.SUCCESS
    } catch (t: Throwable) {
        Logger.w("Unable to query the Play services availability", t)
        false
    }

    private fun batteryLevel(): Int {
        return try {
            val manager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            manager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        } catch (t: Throwable) {
            -1
        }
    }

    /**
     * Small helper so the Google `Task` API can be awaited.
     *
     * The project does not depend on `kotlinx-coroutines-play-services`, so the
     * task is bridged manually instead.
     */
    @SuppressLint("MissingPermission")
    private suspend fun FusedLocationProviderClient.lastLocationCompat(): Location? =
        suspendCancellableCoroutine<Location?> { continuation ->
            try {
                lastLocation
                    .addOnSuccessListener { location ->
                        if (continuation.isActive) continuation.resume(location)
                    }
                    .addOnFailureListener { error ->
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }
            } catch (t: Throwable) {
                if (continuation.isActive) continuation.resumeWithException(t)
            }
        }

    companion object {
        private const val FIX_TIMEOUT_MS = 20_000L
        private const val FIX_INTERVAL_MS = 10_000L
    }
}
