package com.guardian.parent.service.collectors

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.guardian.parent.BuildConfig
import com.guardian.parent.core.CollectorIds
import com.guardian.parent.core.Logger
import com.guardian.parent.data.CollectedItem
import com.guardian.parent.data.HeartbeatPayload
import com.guardian.parent.data.ItemKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Device health snapshot: battery, charging state, network, storage, uptime
 * and whether the screen is on.
 */
class HeartbeatCollector(private val context: Context) : Collector {

    override val id: String = CollectorIds.HEARTBEAT

    override suspend fun collect(): List<CollectedItem> = withContext(Dispatchers.IO) {
        try {
            val battery = readBattery()
            val payload = HeartbeatPayload(
                battery = battery.first,
                charging = battery.second,
                wifi = readWifiSsid(),
                networkType = readNetworkType(),
                storageFreeMb = readFreeStorageMb(),
                uptimeSeconds = SystemClock.elapsedRealtime() / 1000L,
                screenOn = isScreenOn(),
                appVersion = BuildConfig.VERSION_NAME
            )
            listOf(CollectedItem(ItemKind.HEARTBEAT, payload = payload.toJson()))
        } catch (t: Throwable) {
            Logger.e("Heartbeat collection failed", t)
            emptyList()
        }
    }

    private fun readBattery(): Pair<Int, Boolean> {
        val manager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val level = manager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        // The sticky ACTION_BATTERY_CHANGED broadcast carries the charging state.
        // RECEIVER_NOT_EXPORTED is mandatory from Android 14 on.
        val status = ContextCompat.registerReceiver(
            context,
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        return Pair(level.coerceAtLeast(0), charging)
    }

    @Suppress("DEPRECATION")
    private fun readWifiSsid(): String? {
        return try {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE)
                as? WifiManager ?: return null
            val info = wifi.connectionInfo ?: return null
            val ssid = info.ssid?.trim('"')
            when {
                ssid.isNullOrEmpty() -> null
                ssid == "0x" -> null // unknown ssid
                ssid == UNKNOWN_SSID -> null // redacted because of a missing location permission
                else -> ssid
            }
        } catch (t: Throwable) {
            Logger.w("Unable to read the WiFi SSID", t)
            null
        }
    }

    private fun readNetworkType(): String {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? ConnectivityManager ?: return "unknown"
        return try {
            val network = manager.activeNetwork ?: return "none"
            val caps = manager.getNetworkCapabilities(network) ?: return "none"
            when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
                else -> "other"
            }
        } catch (t: Throwable) {
            Logger.w("Unable to read the network type", t)
            "unknown"
        }
    }

    private fun readFreeStorageMb(): Long {
        return try {
            val stat = StatFs(Environment.getDataDirectory().path)
            stat.availableBytes / (1024L * 1024L)
        } catch (t: Throwable) {
            Logger.w("Unable to read the free storage", t)
            0L
        }
    }

    private fun isScreenOn(): Boolean {
        return try {
            val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
                power.isInteractive
            } else {
                @Suppress("DEPRECATION")
                power.isScreenOn
            }
        } catch (t: Throwable) {
            Logger.w("Unable to read the screen state", t)
            false
        }
    }

    companion object {
        /** The value the platform reports when the SSID was redacted. */
        private const val UNKNOWN_SSID = "<unknown ssid>"
    }
}
