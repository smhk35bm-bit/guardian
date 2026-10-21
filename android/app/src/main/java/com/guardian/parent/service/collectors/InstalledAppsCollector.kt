package com.guardian.parent.service.collectors

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import androidx.core.content.pm.PackageInfoCompat
import com.guardian.parent.core.CollectorIds
import com.guardian.parent.core.Logger
import com.guardian.parent.core.jsonArray
import com.guardian.parent.core.jsonObject
import com.guardian.parent.data.AppInfoPayload
import com.guardian.parent.data.CollectedItem
import com.guardian.parent.data.ItemKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Lists the applications installed on the device.
 *
 * On Android 11+ only the packages that the app can "see" are returned. The
 * manifest declares a `<queries>` element for the launcher intent so that all
 * launchable (user visible) applications are covered without asking for the
 * very broad `QUERY_ALL_PACKAGES` permission.
 */
class InstalledAppsCollector(private val context: Context) : Collector {

    override val id: String = CollectorIds.APPS

    override suspend fun collect(): List<CollectedItem> = withContext(Dispatchers.IO) {
        try {
            val pm = context.packageManager
            val packages = pm.getInstalledPackages(0)
            val apps = packages.map { info ->
                AppInfoPayload(
                    packageName = info.packageName,
                    label = labelOf(pm, info),
                    version = versionNameOf(info),
                    system = isSystemApp(info),
                    installedAt = info.firstInstallTime
                )
            }.sortedBy { it.packageName }

            Logger.d("Collected ${apps.size} installed applications")
            val payload = jsonObject {
                put("apps", jsonArray { apps.forEach { app -> put(app.toJson()) } })
            }
            listOf(CollectedItem(ItemKind.APPS, payload = payload))
        } catch (t: Throwable) {
            Logger.e("Installed applications collection failed", t)
            emptyList()
        }
    }

    private fun labelOf(pm: android.content.pm.PackageManager, info: PackageInfo): String? = try {
        val applicationInfo = info.applicationInfo
        if (applicationInfo == null) null else pm.getApplicationLabel(applicationInfo).toString()
    } catch (t: Throwable) {
        null
    }

    private fun versionNameOf(info: PackageInfo): String? = try {
        val code = PackageInfoCompat.getLongVersionCode(info)
        if (info.versionName.isNullOrEmpty()) "code:$code" else "${info.versionName} ($code)"
    } catch (t: Throwable) {
        null
    }

    private fun isSystemApp(info: PackageInfo): Boolean {
        val flags = info.applicationInfo?.flags ?: return false
        val systemMask = ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
        return (flags and systemMask) != 0
    }
}
