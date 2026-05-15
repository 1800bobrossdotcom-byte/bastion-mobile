package cam.bastion.mobile.acoustic

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.*
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import cam.bastion.mobile.MainActivity
import cam.bastion.mobile.R
import kotlinx.coroutines.*
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Acoustic Shield — generates audio designed to obstruct nearby microphone recording.
 *
 * Modes (ported from cbay.ing/shield):
 *   - SWEEP:     sawtooth swept by triangle LFO around 2.75 kHz, bandpass-filtered (mic-band jam)
 *   - BROADBAND: filtered white noise centred on 2 kHz (raises noise floor in speech band)
 *   - COUNTER:   stacked harmonics of `targetFreq` (anti-recording overlay)
 *   - PHASE:     read live mic, invert + tiny delay, play back (only useful in headphones / field)
 *
 * HONESTY constraint: phone speakers cap ~85 dB SPL and have negligible output above ~16 kHz.
 * This will degrade nearby smartphone-quality recordings of speech in close proximity. It will
 * NOT defeat directional mics, won't reach LRAD-class output, won't "block" anything — it
 * raises the noise floor in the band where speech mics are most sensitive.
 */
class AcousticShieldService : Service() {

    enum class Mode { OFF, SWEEP, BROADBAND, COUNTER, PHASE }

    companion object {
        private const val TAG = "AcousticShield"
        private const val NOTIF_ID = 2001
        private const val CHANNEL_ID = "bastion-acoustic"

        const val EXTRA_MODE = "mode"
        const val EXTRA_VOLUME = "volume"      // 0..1
        const val EXTRA_INTENSITY = "intensity" // 0..1
        const val EXTRA_TARGET = "target"      // Hz, used by COUNTER

        const val SAMPLE_RATE = 44100

        @Volatile var currentMode: Mode = Mode.OFF; private set
        @Volatile var currentVolume: Float = 0.6f; private set
        @Volatile var currentIntensity: Float = 0.7f; private set
        @Volatile var currentTarget: Int = 1000; private set
        /** RMS amplitude of the most recent rendered chunk, 0..1. Read by UI for live meter. */
        @Volatile var outputLevel: Float = 0f; private set
        internal fun publishLevel(v: Float) { outputLevel = v }

        const val ACTION_UPDATE = "cam.bastion.mobile.acoustic.UPDATE"

        fun start(ctx: Context, mode: Mode, volume: Float, intensity: Float, target: Int) {
            val i = Intent(ctx, AcousticShieldService::class.java)
                .putExtra(EXTRA_MODE, mode.name)
                .putExtra(EXTRA_VOLUME, volume)
                .putExtra(EXTRA_INTENSITY, intensity)
                .putExtra(EXTRA_TARGET, target)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
        }

        /** Update parameters live without restarting the audio stream. Use during slider drag. */
        fun updateParams(volume: Float, intensity: Float, target: Int) {
            currentVolume = volume
            currentIntensity = intensity
            currentTarget = target
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, AcousticShieldService::class.java))
            outputLevel = 0f
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var job: Job? = null
    @Volatile private var track: AudioTrack? = null
    @Volatile private var record: AudioRecord? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val mode = runCatching { Mode.valueOf(intent?.getStringExtra(EXTRA_MODE) ?: "OFF") }
            .getOrDefault(Mode.OFF)
        currentVolume = intent?.getFloatExtra(EXTRA_VOLUME, 0.6f) ?: 0.6f
        currentIntensity = intent?.getFloatExtra(EXTRA_INTENSITY, 0.7f) ?: 0.7f
        currentTarget = intent?.getIntExtra(EXTRA_TARGET, 1000) ?: 1000

        // Pick FGS type per mode. PHASE needs mic; others are pure playback.
        // Calling startForeground with the right type avoids SecurityException on API 34+.
        val fgsType = if (mode == Mode.PHASE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        }
        try {
            ServiceCompat.startForeground(this, NOTIF_ID, notification(mode), fgsType)
        } catch (t: Throwable) {
            Log.e(TAG, "startForeground failed: ${t.message}", t)
            stopSelf()
            return START_NOT_STICKY
        }

        if (mode == Mode.OFF) {
            stopSelf()
            return START_NOT_STICKY
        }
        // If already running this mode, just keep going — params are already updated above.
        if (mode == currentMode && job?.isActive == true) {
            return START_STICKY
        }
        currentMode = mode
        runMode(mode)
        return START_STICKY
    }

    private fun runMode(mode: Mode) {
        job?.cancel()
        teardownAudio()

        val minBuf = AudioTrack.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT
        ).coerceAtLeast(2048)

        val newTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(minBuf * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track = newTrack
        newTrack.play()

        job = scope.launch {
            try {
                when (mode) {
                    Mode.SWEEP -> renderSweep(newTrack)
                    Mode.BROADBAND -> renderBroadband(newTrack)
                    Mode.COUNTER -> renderCounter(newTrack)
                    Mode.PHASE -> renderPhaseCancel(newTrack)
                    Mode.OFF -> Unit
                }
            } catch (t: Throwable) {
                if (t !is CancellationException) Log.w(TAG, "render failed: ${t.message}", t)
            }
        }
    }

    /* ------------------------------------------------------------------ DSP ------- */

    /** Sawtooth swept ±1.25 kHz around 2.75 kHz by 2 Hz triangle LFO, bandpassed. */
    private suspend fun renderSweep(t: AudioTrack) = withContext(Dispatchers.IO) {
        val frame = ShortBuffer(2048)
        var sawPhase = 0.0
        var lfoPhase = 0.0
        val lfoHz = 2.0
        val sweepDepth = 1250.0
        val centre = 2750.0
        val bp = Biquad.bandpass(centre, q = 2.0)
        while (isActive) {
            val gain = 0.3f * currentIntensity * currentVolume
            for (i in 0 until frame.size) {
                val lfo = triangle(lfoPhase) // -1..1
                lfoPhase += lfoHz / SAMPLE_RATE; if (lfoPhase >= 1) lfoPhase -= 1
                val freq = (centre + sweepDepth * lfo).coerceIn(80.0, 18000.0)
                val s = (sawPhase * 2.0 - 1.0).toFloat()
                sawPhase += freq / SAMPLE_RATE; if (sawPhase >= 1) sawPhase -= 1
                val filtered = bp.process(s.toDouble()).toFloat() * gain
                frame.set(i, filtered)
            }
            t.write(frame.data, 0, frame.size, AudioTrack.WRITE_BLOCKING)
            publishLevel(rms(frame.data))
        }
    }

    /** Bandpass-filtered white noise centred on 2 kHz, Q=0.5. */
    private suspend fun renderBroadband(t: AudioTrack) = withContext(Dispatchers.IO) {
        val frame = ShortBuffer(2048)
        val bp = Biquad.bandpass(2000.0, q = 0.5)
        val rng = Random.Default
        while (isActive) {
            val gain = 0.4f * currentIntensity * currentVolume
            for (i in 0 until frame.size) {
                val s = (rng.nextFloat() * 2f - 1f).toDouble()
                frame.set(i, (bp.process(s) * gain).toFloat())
            }
            t.write(frame.data, 0, frame.size, AudioTrack.WRITE_BLOCKING)
            publishLevel(rms(frame.data))
        }
    }

    /** Stacked harmonics 2..5 of targetFreq, slightly detuned per harmonic. */
    private suspend fun renderCounter(t: AudioTrack) = withContext(Dispatchers.IO) {
        val frame = ShortBuffer(2048)
        val phases = DoubleArray(4) { 0.0 }
        var lastBase = -1.0
        var freqs = DoubleArray(4)
        while (isActive) {
            val base = currentTarget.toDouble().coerceIn(50.0, 6000.0)
            if (base != lastBase) {
                for (h in 0..3) {
                    val H = h + 2
                    freqs[h] = base * H + 3.7 * (H - 1)
                }
                lastBase = base
            }
            val gain = 0.25f * currentIntensity * currentVolume
            for (i in 0 until frame.size) {
                var s = 0f
                for (h in 0..3) {
                    s += (sin(phases[h] * 2 * PI) * gain / (h + 2)).toFloat()
                    phases[h] += freqs[h] / SAMPLE_RATE
                    if (phases[h] >= 1) phases[h] -= 1
                }
                frame.set(i, s)
            }
            t.write(frame.data, 0, frame.size, AudioTrack.WRITE_BLOCKING)
            publishLevel(rms(frame.data))
        }
    }

    /** Phase-cancel: read mic, invert sample + ~5 ms delay, play back.
     *  Useful only for headphone / personal-bubble use; speaker→mic loop will feed back. */
    private suspend fun renderPhaseCancel(t: AudioTrack) = withContext(Dispatchers.IO) {
        val minRec = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT
        ).coerceAtLeast(2048)
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_FLOAT, minRec * 2
            )
        } catch (_: SecurityException) {
            Log.w(TAG, "RECORD_AUDIO permission missing — phase cancel disabled")
            return@withContext
        }
        record = rec
        rec.startRecording()
        val bufSize = 1024
        val read = FloatArray(bufSize)
        val out = FloatArray(bufSize)
        // ~5 ms delay ring (220 samples @ 44.1kHz)
        val delaySize = 220
        val ring = FloatArray(delaySize)
        var ringIdx = 0
        while (isActive) {
            val n = rec.read(read, 0, bufSize, AudioRecord.READ_BLOCKING)
            if (n <= 0) continue
            val gain = currentVolume
            for (i in 0 until n) {
                val delayed = ring[ringIdx]
                ring[ringIdx] = read[i]
                ringIdx = (ringIdx + 1) % delaySize
                out[i] = -delayed * gain
            }
            t.write(out, 0, n, AudioTrack.WRITE_BLOCKING)
            publishLevel(rms(out, n))
        }
    }

    /* ----------------------------------------------------------------- helpers --- */

    private fun teardownAudio() {
        try { track?.stop() } catch (_: Throwable) {}
        try { track?.release() } catch (_: Throwable) {}
        track = null
        try { record?.stop() } catch (_: Throwable) {}
        try { record?.release() } catch (_: Throwable) {}
        record = null
    }

    private fun triangle(phase: Double): Double {
        // -1..1 triangle from 0..1 phase
        return if (phase < 0.5) -1.0 + 4.0 * phase else 3.0 - 4.0 * phase
    }

    private fun rms(buf: FloatArray, n: Int = buf.size): Float {
        if (n <= 0) return 0f
        var sum = 0.0
        var i = 0
        while (i < n) { val v = buf[i]; sum += v * v; i++ }
        return sqrt(sum / n).toFloat().coerceIn(0f, 1f)
    }

    private fun notification(mode: Mode): Notification {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.acoustic_channel_name),
                    NotificationManager.IMPORTANCE_LOW)
            )
        }
        val tap = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.acoustic_notification_title))
            .setContentText("Mode: ${mode.name}")
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
            .setContentIntent(tap)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        currentMode = Mode.OFF
        outputLevel = 0f
        job?.cancel()
        scope.cancel()
        teardownAudio()
        super.onDestroy()
    }
}

/** Reusable float frame buffer (avoids allocs per loop iter). */
private class ShortBuffer(val size: Int) {
    val data: FloatArray = FloatArray(size)
    fun set(i: Int, v: Float) { data[i] = v.coerceIn(-1f, 1f) }
}

/** Minimal direct-form-I biquad. Bandpass (constant skirt gain). */
internal class Biquad private constructor(
    private val b0: Double, private val b1: Double, private val b2: Double,
    private val a1: Double, private val a2: Double,
) {
    private var z1 = 0.0
    private var z2 = 0.0
    fun process(x: Double): Double {
        val y = b0 * x + z1
        z1 = b1 * x - a1 * y + z2
        z2 = b2 * x - a2 * y
        return y
    }
    companion object {
        fun bandpass(centreHz: Double, q: Double, sampleRate: Int = AcousticShieldService.SAMPLE_RATE): Biquad {
            val w0 = 2.0 * PI * centreHz / sampleRate
            val alpha = sin(w0) / (2.0 * q)
            val cosw = kotlin.math.cos(w0)
            val a0 = 1.0 + alpha
            return Biquad(
                b0 = alpha / a0,
                b1 = 0.0,
                b2 = -alpha / a0,
                a1 = -2.0 * cosw / a0,
                a2 = (1.0 - alpha) / a0,
            )
        }
    }
}
