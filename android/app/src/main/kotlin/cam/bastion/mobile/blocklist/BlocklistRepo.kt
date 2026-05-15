package cam.bastion.mobile.blocklist

import android.content.Context
import android.util.Log
import cam.bastion.mobile.settings.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * In-memory blocklist of malicious hostnames. Source: bastion-mobile gh-pages.
 * Refreshed every 12h. Cached on disk so first-run-offline still works after first fetch.
 */
object BlocklistRepo {
    private const val URL =
        "https://1800bobrossdotcom-byte.github.io/bastion-mobile/blocklist.txt"
    private const val CACHE_FILE = "blocklist.txt"
    private const val MAX_AGE_MS = 12L * 60 * 60 * 1000

    @Volatile private var hosts: Set<String> = emptySet()
    val hostCount: Int get() = hosts.size

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    fun isBlocked(host: String): Boolean {
        if (hosts.isEmpty()) return false
        val normalized = host.lowercase().trimEnd('.')
        if (normalized in hosts) return true
        // Match parent domain, e.g. "ads.bad.example.com" matches "bad.example.com" if listed.
        var i = normalized.indexOf('.')
        while (i in 0 until normalized.length - 1) {
            if (normalized.substring(i + 1) in hosts) return true
            i = normalized.indexOf('.', i + 1)
        }
        return false
    }

    suspend fun refreshIfStale(ctx: Context) = withContext(Dispatchers.IO) {
        val cache = File(ctx.filesDir, CACHE_FILE)
        val fresh = cache.exists() && System.currentTimeMillis() - cache.lastModified() < MAX_AGE_MS
        if (!fresh) doFetch(ctx, cache)
        if (cache.exists()) load(cache)
    }

    /** Returns true if a successful fetch happened. */
    suspend fun forceRefresh(ctx: Context): Boolean = withContext(Dispatchers.IO) {
        val cache = File(ctx.filesDir, CACHE_FILE)
        val ok = doFetch(ctx, cache)
        if (cache.exists()) load(cache)
        ok
    }

    private fun doFetch(ctx: Context, cache: File): Boolean {
        return try {
            val resp = client.newCall(Request.Builder().url(URL).build()).execute()
            resp.use {
                if (it.isSuccessful) {
                    val body = it.body?.string().orEmpty()
                    if (body.isNotBlank()) {
                        cache.writeText(body)
                        Settings.setLastRefresh(ctx, System.currentTimeMillis())
                        return@use true
                    }
                }
                false
            }
        } catch (t: Throwable) {
            Log.w("BlocklistRepo", "fetch failed: ${t.message}")
            false
        }
    }

    private fun load(file: File) {
        val set = HashSet<String>(200_000)
        file.bufferedReader().useLines { lines ->
            for (raw in lines) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                set.add(line.lowercase().trimEnd('.'))
            }
        }
        hosts = set
        Log.i("BlocklistRepo", "loaded ${set.size} hosts")
    }
}
