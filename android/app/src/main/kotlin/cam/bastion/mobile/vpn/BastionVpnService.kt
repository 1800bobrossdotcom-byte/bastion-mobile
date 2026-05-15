package cam.bastion.mobile.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import cam.bastion.mobile.MainActivity
import cam.bastion.mobile.R
import cam.bastion.mobile.audit.AuditDb
import cam.bastion.mobile.audit.AuditEvent
import cam.bastion.mobile.blocklist.BlocklistRepo
import cam.bastion.mobile.settings.Settings
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger

/**
 * Hostnames that the Android system uses to validate that the active network
 * has working internet. If we ever NXDOMAIN one of these (or our response
 * packet is malformed enough that the kernel drops it), Android marks the
 * network as "no internet" and the flag survives the VPN being torn down
 * — the user has to reset network settings to recover. Always fast-forward
 * these to upstream and never apply blocklist matching to them.
 */
private val SYSTEM_PROBE_HOSTS = setOf(
    "connectivitycheck.gstatic.com",
    "connectivitycheck.android.com",
    "www.google.com",
    "clients3.google.com",
    "clients4.google.com",
    "play.googleapis.com",
    "mtalk.google.com",
    "android.clients.google.com",
    "captive.apple.com",
)

/**
 * BASTION DNS sinkhole VPN.
 *
 * Implementation status:
 *   - VpnService established + foreground notification: WORKING
 *   - Blocklist fetch + cache: WORKING
 *   - DNS query parsing + sinkhole + upstream forward: SCAFFOLDED, see runDnsLoop()
 *
 * The runDnsLoop function is the heart. It reads UDP packets from the tun fd,
 * parses DNS questions, drops queries for blocklisted hostnames (responds NXDOMAIN
 * directly), and forwards everything else to upstream resolver 1.1.1.1.
 *
 * NOTE: This skeleton intentionally keeps the IP+UDP packet construction simple.
 * Production implementation should handle IPv4/IPv6 + DNS-over-HTTPS opt-out.
 */
class BastionVpnService : VpnService() {

    companion object {
        private const val TAG = "BastionVPN"
        private const val NOTIF_ID = 1001
        private const val CHANNEL_ID = "bastion-sensor"
        const val ACTION_STOP = "cam.bastion.mobile.vpn.STOP"
        /** True between onStartCommand returning and onDestroy completing. UI reads this. */
        @Volatile var isRunning: Boolean = false; private set
    }

    private var tun: ParcelFileDescriptor? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Honour explicit stop requests synchronously. We were started via
        // startForegroundService() so we MUST call startForeground at least once
        // before stopping, otherwise Android crashes the process.
        if (intent?.action == ACTION_STOP) {
            try { startForeground(NOTIF_ID, buildNotification()) } catch (_: Throwable) {}
            teardown()
            return START_NOT_STICKY
        }
        startForeground(NOTIF_ID, buildNotification())
        startVpn()
        isRunning = true
        return START_NOT_STICKY
    }

    /**
     * Synchronous teardown. Flips isRunning false BEFORE removing the
     * notification so the polling UI sees "OFF" on its next tick. Closing the
     * tun fd makes the blocking input.read() in runDnsLoop return -1 immediately
     * so the DNS coroutine exits without waiting for cancellation. This is what
     * actually restores the phone's internet — without it the OS keeps the
     * route to the upstream DNS server pinned for several seconds.
     */
    private fun teardown() {
        isRunning = false
        try { tun?.close() } catch (_: Throwable) {}
        tun = null
        scope.cancel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION") stopForeground(true)
        }
        stopSelf()
    }

    private fun startVpn() {
        val upstream = Settings.resolver(applicationContext).ip
        // DNS-ONLY ROUTING: only packets destined for the upstream DNS server
        // travel through our tun. Everything else (web, apps, IPv6) keeps using
        // the underlying network. Without this, the entire phone's internet
        // dies the moment we capture 0.0.0.0/0 because we drop non-DNS packets.
        tun = Builder()
            .setSession("BASTION")
            .addAddress("10.42.0.2", 30)
            .addRoute(upstream, 32)
            .addDnsServer(upstream)
            .allowFamily(android.system.OsConstants.AF_INET)
            .setMtu(1500)
            .establish() ?: run {
                Log.e(TAG, "VpnService.Builder.establish() returned null")
                stopSelf()
                return
            }

        scope.launch {
            BlocklistRepo.refreshIfStale(applicationContext)
            runDnsLoop(tun!!, upstream)
        }
    }

    /**
     * Reads packets from the tun, parses DNS, sinkholes blocked hostnames,
     * forwards everything else.
     *
     * Concurrency model: the read loop dispatches each query to its OWN
     * coroutine + its OWN protect()'d DatagramSocket. A single shared socket
     * causes responses to land out of order — the wrong response then gets
     * wrapped with the wrong source IP/port and the querying app's resolver
     * discards it on transaction-ID mismatch, eventually killing all DNS
     * resolution and giving the user the impression that internet is broken.
     */
    private suspend fun runDnsLoop(tun: ParcelFileDescriptor, upstreamIp: String) = withContext(Dispatchers.IO) {
        val input = FileInputStream(tun.fileDescriptor)
        val output = FileOutputStream(tun.fileDescriptor)
        val writeMutex = Mutex()
        val buf = ByteArray(32_767)
        val auditDao = AuditDb.get(applicationContext).dao()

        while (isActive) {
            val n = try { input.read(buf) } catch (_: Throwable) { break }
            if (n <= 0) continue

            // Minimal IPv4 + UDP detection. v=4, proto=17 (UDP), dst port 53.
            val version = (buf[0].toInt() and 0xF0) shr 4
            if (version != 4) continue
            val ihl = (buf[0].toInt() and 0x0F) * 4
            val proto = buf[9].toInt() and 0xFF
            if (proto != 17) continue
            val dstPort = ((buf[ihl + 2].toInt() and 0xFF) shl 8) or (buf[ihl + 3].toInt() and 0xFF)
            if (dstPort != 53) continue

            val udpHeaderEnd = ihl + 8
            if (n <= udpHeaderEnd + 12) continue

            // Snapshot per-packet fields BEFORE buf is reused on the next read.
            val dnsPayload = buf.copyOfRange(udpHeaderEnd, n)
            val originalSrcAddr = buf.copyOfRange(12, 16)  // app
            val originalDstAddr = buf.copyOfRange(16, 20)  // upstream resolver
            val originalSrcPort = ((buf[ihl].toInt() and 0xFF) shl 8) or (buf[ihl + 1].toInt() and 0xFF)

            val hostname = parseDnsQuestion(dnsPayload) ?: continue
            val isProbe = hostname.lowercase().trimEnd('.') in SYSTEM_PROBE_HOSTS
            if (!isProbe && BlocklistRepo.isBlocked(hostname)) {
                Log.i(TAG, "BLOCKED $hostname")
                runCatching {
                    auditDao.insert(AuditEvent(ts = System.currentTimeMillis(),
                        host = hostname, action = "BLOCKED"))
                }
                // Synthesize NXDOMAIN so the app fails fast instead of waiting
                // out its DNS timeout (which makes the phone feel "broken").
                val nx = synthesizeNxDomain(dnsPayload)
                val wrapped = wrapIpv4Udp(
                    srcAddr = originalDstAddr, dstAddr = originalSrcAddr,
                    srcPort = 53, dstPort = originalSrcPort, payload = nx,
                )
                runCatching { writeMutex.withLock { output.write(wrapped) } }
                continue
            }

            // Forward to upstream resolver — own socket per query, fire-and-forget.
            scope.launch {
                val sock = try {
                    DatagramSocket().also { protect(it); it.soTimeout = 5000 }
                } catch (t: Throwable) {
                    Log.w(TAG, "socket alloc failed: ${t.message}"); return@launch
                }
                try {
                    sock.send(DatagramPacket(dnsPayload, dnsPayload.size,
                        InetAddress.getByName(upstreamIp), 53))
                    val resp = ByteArray(4096)
                    val respPkt = DatagramPacket(resp, resp.size)
                    sock.receive(respPkt)
                    val wrapped = wrapIpv4Udp(
                        srcAddr = originalDstAddr, dstAddr = originalSrcAddr,
                        srcPort = 53, dstPort = originalSrcPort,
                        payload = resp.copyOfRange(0, respPkt.length),
                    )
                    writeMutex.withLock { output.write(wrapped) }
                } catch (t: Throwable) {
                    Log.w(TAG, "upstream DNS error for $hostname: ${t.message}")
                } finally {
                    runCatching { sock.close() }
                }
            }
        }
    }

    /**
     * Build a NXDOMAIN response from the original query that includes a SOA
     * record in the AUTHORITY section so resolvers cap negative-cache TTL
     * at 60 seconds (RFC 2308). Without a SOA, some Android resolvers cache
     * NXDOMAINs indefinitely — a single false-positive block would then
     * persist for the lifetime of the network connection.
     */
    private fun synthesizeNxDomain(query: ByteArray): ByteArray {
        if (query.size < 12) return query
        // Find end of question section (skip name + 4 bytes for QTYPE+QCLASS).
        var qEnd = 12
        while (qEnd < query.size) {
            val len = query[qEnd].toInt() and 0xFF
            if (len == 0) { qEnd += 1; break }
            if (len > 63 || qEnd + 1 + len > query.size) return query
            qEnd += 1 + len
        }
        qEnd += 4 // QTYPE + QCLASS
        if (qEnd > query.size) return query

        // SOA RR for root ".", TTL=60, MNAME="." (1 byte 0x00), RNAME="." (1 byte 0x00),
        // SERIAL=1, REFRESH/RETRY/EXPIRE=3600/600/86400, MINIMUM=60.
        val soa = ByteBuffer.allocate(35).apply {
            put(0x00.toByte())                       // NAME = root
            putShort(0x0006.toShort())               // TYPE = SOA
            putShort(0x0001.toShort())               // CLASS = IN
            putInt(60)                               // TTL
            putShort(22.toShort())                   // RDLENGTH
            put(0x00.toByte())                       // MNAME root
            put(0x00.toByte())                       // RNAME root
            putInt(1)                                // SERIAL
            putInt(3600); putInt(600); putInt(86400) // REFRESH/RETRY/EXPIRE
            putInt(60)                               // MINIMUM (the negative-cache TTL)
        }.array()

        val resp = ByteBuffer.allocate(qEnd + soa.size)
        resp.put(query, 0, qEnd)
        resp.put(soa)
        val out = resp.array()
        // Header: QR=1, RA=1, RCODE=3 (NXDOMAIN), preserve RD.
        out[2] = ((query[2].toInt() and 0x01) or 0x80).toByte()
        out[3] = (0x80 or 0x03).toByte()
        // ANCOUNT=0, NSCOUNT=1, ARCOUNT=0
        out[6] = 0; out[7] = 0
        out[8] = 0; out[9] = 1
        out[10] = 0; out[11] = 0
        return out
    }

    private fun parseDnsQuestion(dns: ByteArray): String? {
        if (dns.size < 13) return null
        var i = 12
        val labels = mutableListOf<String>()
        while (i < dns.size) {
            val len = dns[i].toInt() and 0xFF
            if (len == 0) break
            if (len > 63 || i + 1 + len > dns.size) return null
            labels.add(String(dns, i + 1, len))
            i += 1 + len
        }
        return if (labels.isEmpty()) null else labels.joinToString(".")
    }

    private val ipIdCounter = AtomicInteger(1)

    private fun wrapIpv4Udp(srcAddr: ByteArray, dstAddr: ByteArray,
                            srcPort: Int, dstPort: Int, payload: ByteArray): ByteArray {
        val totalLen = 20 + 8 + payload.size
        val out = ByteBuffer.allocate(totalLen)
        // IPv4 header
        out.put(0x45.toByte())                              // ver+ihl
        out.put(0)                                          // dscp
        out.putShort(totalLen.toShort())                    // total len
        out.putShort((ipIdCounter.getAndIncrement() and 0xFFFF).toShort()) // id
        out.putShort(0x4000.toShort())                      // flags=DF
        out.put(64)                                         // ttl
        out.put(17)                                         // proto=UDP
        out.putShort(0)                                     // checksum (computed below)
        out.put(srcAddr); out.put(dstAddr)
        // recompute IP checksum
        val ip = out.array()
        var sum = 0
        for (k in 0 until 20 step 2) sum += ((ip[k].toInt() and 0xFF) shl 8) or (ip[k + 1].toInt() and 0xFF)
        while (sum shr 16 != 0) sum = (sum and 0xFFFF) + (sum shr 16)
        val cksum = sum.inv() and 0xFFFF
        ip[10] = (cksum shr 8).toByte(); ip[11] = (cksum and 0xFF).toByte()
        // UDP header
        val udpLen = 8 + payload.size
        out.putShort(srcPort.toShort())
        out.putShort(dstPort.toShort())
        out.putShort(udpLen.toShort())
        out.putShort(0)                                     // checksum placeholder
        out.put(payload)
        // Compute UDP checksum over pseudo-header + UDP header + payload.
        // Some Android cellular paths drop UDP/53 packets with checksum=0 as
        // malformed, which manifests as ConnectivityService marking the network
        // "no internet" — a state that survives the VPN tearing down.
        val arr = out.array()
        var us = 0
        // pseudo-header: src(4) + dst(4) + 0x00 + proto(1) + udpLen(2)
        for (k in 12 until 20 step 2) us += ((arr[k].toInt() and 0xFF) shl 8) or (arr[k + 1].toInt() and 0xFF)
        us += 17                          // protocol
        us += udpLen                      // udp length (again)
        // udp header + payload, treating payload as 16-bit words (pad odd byte with 0)
        var k = 20
        while (k + 1 < arr.size) {
            us += ((arr[k].toInt() and 0xFF) shl 8) or (arr[k + 1].toInt() and 0xFF)
            k += 2
        }
        if (k < arr.size) us += (arr[k].toInt() and 0xFF) shl 8
        while (us shr 16 != 0) us = (us and 0xFFFF) + (us shr 16)
        var udpCk = us.inv() and 0xFFFF
        if (udpCk == 0) udpCk = 0xFFFF    // RFC 768: 0 means "no checksum", use 0xFFFF instead
        arr[26] = (udpCk shr 8).toByte(); arr[27] = (udpCk and 0xFF).toByte()
        return arr
    }

    override fun onRevoke() {
        // User killed the VPN from system settings.
        teardown()
        super.onRevoke()
    }

    override fun onDestroy() {
        isRunning = false
        scope.cancel()
        try { tun?.close() } catch (_: Throwable) {}
        tun = null
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.vpn_channel_name),
                    NotificationManager.IMPORTANCE_LOW)
            )
        }
        val tap = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.vpn_notification_title))
            .setContentText(getString(R.string.vpn_notification_body))
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(tap)
            .setOngoing(true)
            .build()
    }
}
