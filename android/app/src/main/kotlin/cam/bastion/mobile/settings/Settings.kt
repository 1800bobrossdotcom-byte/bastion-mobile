package cam.bastion.mobile.settings

import android.content.Context
import androidx.core.content.edit

/**
 * v0.3.0 redesign: blocking is no longer done on-device. Instead, the user
 * sets Android's system **Private DNS** to a hosted DoT resolver that filters
 * malware + ads + trackers. This object stores the user's chosen provider.
 *
 * The hostname is the value the user types (or we deep-link them to type)
 * into Settings → Network → Private DNS.
 */
object Settings {
    private const val PREFS = "bastion-settings"
    private const val K_PROVIDER = "provider"

    /**
     * Curated list of public DoT resolvers that block malware/ads at the DNS
     * layer. The hosted bastion resolver (`dns.bastion.cam`) is listed but
     * marked unavailable until our infra is live; UI grays it out.
     */
    enum class Provider(
        val hostname: String,
        val short: String,
        val description: String,
        val available: Boolean = true,
    ) {
        BASTION(
            hostname = "dns.bastion.cam",
            short = "BASTION",
            description = "Self-hosted blocklist (URLhaus + OpenPhish + ads). Coming soon.",
            available = false,
        ),
        ADGUARD(
            hostname = "dns.adguard-dns.com",
            short = "ADGUARD",
            description = "Blocks ads + trackers + malware. Recommended default.",
        ),
        ADGUARD_FAMILY(
            hostname = "family.adguard-dns.com",
            short = "ADGUARD FAMILY",
            description = "AdGuard + adult content + parental controls.",
        ),
        CLOUDFLARE_SECURITY(
            hostname = "security.cloudflare-dns.com",
            short = "CLOUDFLARE",
            description = "Blocks known malware. Fast, no ad/tracker blocking.",
        ),
        CLOUDFLARE_FAMILY(
            hostname = "family.cloudflare-dns.com",
            short = "CLOUDFLARE FAMILY",
            description = "Cloudflare malware + adult content.",
        ),
        QUAD9(
            hostname = "dns.quad9.net",
            short = "QUAD9",
            description = "Swiss non-profit, blocks malware + phishing.",
        );

        companion object {
            fun fromHostname(h: String?): Provider =
                entries.firstOrNull { it.hostname == h } ?: ADGUARD
        }
    }

    fun provider(ctx: Context): Provider =
        Provider.fromHostname(prefs(ctx).getString(K_PROVIDER, null))

    fun setProvider(ctx: Context, p: Provider) {
        prefs(ctx).edit { putString(K_PROVIDER, p.hostname) }
    }

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
