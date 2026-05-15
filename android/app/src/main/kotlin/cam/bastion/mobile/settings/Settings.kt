package cam.bastion.mobile.settings

import android.content.Context
import androidx.core.content.edit

/**
 * Lightweight key/value settings backed by SharedPreferences.
 * No DataStore dependency to keep deps small.
 */
object Settings {
    private const val PREFS = "bastion-settings"
    private const val K_RESOLVER = "resolver"
    private const val K_LAST_REFRESH = "blocklist_last_refresh"

    enum class Resolver(val ip: String, val label: String) {
        CLOUDFLARE("1.1.1.1", "Cloudflare 1.1.1.1"),
        QUAD9("9.9.9.9", "Quad9 9.9.9.9 (malware-filtered upstream)"),
        GOOGLE("8.8.8.8", "Google 8.8.8.8");

        companion object {
            fun fromIp(ip: String?): Resolver =
                entries.firstOrNull { it.ip == ip } ?: CLOUDFLARE
        }
    }

    fun resolver(ctx: Context): Resolver =
        Resolver.fromIp(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(K_RESOLVER, null))

    fun setResolver(ctx: Context, r: Resolver) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString(K_RESOLVER, r.ip) }
    }

    fun lastRefresh(ctx: Context): Long =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(K_LAST_REFRESH, 0L)

    fun setLastRefresh(ctx: Context, ts: Long) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putLong(K_LAST_REFRESH, ts) }
    }
}
