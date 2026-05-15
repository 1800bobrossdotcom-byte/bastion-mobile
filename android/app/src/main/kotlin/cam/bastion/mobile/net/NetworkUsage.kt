package cam.bastion.mobile.net

import android.app.AppOpsManager
import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Process
import android.provider.Settings

/**
 * Per-app network usage ledger via NetworkStatsManager.
 *
 * Honest scope: this is **billing-grade** data, not packet capture. It tells
 * you bytes-out and bytes-in per UID since boot. You won't see destinations,
 * hostnames, or content. But "Discord uploaded 412 MB while you were asleep"
 * is the kind of pattern that catches data-exfil malware + abusive SDKs.
 *
 * Requires the special PACKAGE_USAGE_STATS permission, granted manually via
 * Settings → Apps → Special access → Usage access. We never ask via the
 * runtime dialog because the OS doesn't allow that for this permission.
 */
object NetworkUsage {

    data class Row(
        val uid: Int,
        val packageName: String,
        val label: String,
        val rxBytes: Long,
        val txBytes: Long,
        val isSystem: Boolean,
    ) {
        val total: Long get() = rxBytes + txBytes
    }

    fun hasUsageStatsPermission(ctx: Context): Boolean {
        val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        val mode = if (Build.VERSION.SDK_INT >= 29) {
            ops.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun openUsageAccessSettings(ctx: Context) {
        runCatching {
            ctx.startActivity(
                Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                    .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure {
            ctx.startActivity(
                Intent(Settings.ACTION_SETTINGS).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    /**
     * Returns rows for every installed app with non-zero traffic since the
     * given epoch ms (default: 24h ago). Sorted by total descending.
     *
     * If permission is missing or NetworkStatsManager throws, returns empty
     * list — callers should gate on [hasUsageStatsPermission] first.
     */
    fun snapshot(ctx: Context, sinceMs: Long = System.currentTimeMillis() - 24L * 3600 * 1000): List<Row> {
        if (!hasUsageStatsPermission(ctx)) return emptyList()
        val nsm = ctx.getSystemService(Context.NETWORK_STATS_SERVICE) as? NetworkStatsManager
            ?: return emptyList()
        val pm = ctx.packageManager
        val now = System.currentTimeMillis()

        val perUid = HashMap<Int, LongArray>() // [rx, tx]
        for (type in intArrayOf(ConnectivityManager.TYPE_WIFI, ConnectivityManager.TYPE_MOBILE)) {
            try {
                val stats = nsm.querySummary(type, null, sinceMs, now)
                val bucket = NetworkStats.Bucket()
                while (stats.hasNextBucket()) {
                    stats.getNextBucket(bucket)
                    val arr = perUid.getOrPut(bucket.uid) { LongArray(2) }
                    arr[0] += bucket.rxBytes
                    arr[1] += bucket.txBytes
                }
                stats.close()
            } catch (_: Throwable) { /* swallow per-type failures */ }
        }
        if (perUid.isEmpty()) return emptyList()

        // Resolve uid → label.
        val pkgs = pm.getInstalledApplications(0)
        val uidToApp = HashMap<Int, ApplicationInfo>(pkgs.size)
        for (a in pkgs) uidToApp.putIfAbsent(a.uid, a)

        val rows = ArrayList<Row>(perUid.size)
        for ((uid, rxTx) in perUid) {
            if (rxTx[0] == 0L && rxTx[1] == 0L) continue
            val app = uidToApp[uid]
            val label = when {
                app != null -> runCatching { pm.getApplicationLabel(app).toString() }
                    .getOrDefault(app.packageName)
                uid == NetworkStats.Bucket.UID_TETHERING -> "(tethering)"
                uid == NetworkStats.Bucket.UID_REMOVED -> "(uninstalled apps)"
                uid == NetworkStats.Bucket.UID_ALL -> "(all)"
                uid < 10000 -> "(system uid $uid)"
                else -> "uid $uid"
            }
            rows += Row(
                uid = uid,
                packageName = app?.packageName ?: "",
                label = label,
                rxBytes = rxTx[0],
                txBytes = rxTx[1],
                isSystem = (app?.flags ?: 0) and ApplicationInfo.FLAG_SYSTEM != 0,
            )
        }
        rows.sortByDescending { it.total }
        return rows
    }

    fun activeNetworkLabel(ctx: Context): String {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return "unknown"
        val net = cm.activeNetwork ?: return "offline"
        val caps = cm.getNetworkCapabilities(net) ?: return "unknown"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
            else -> "other"
        }
    }
}
