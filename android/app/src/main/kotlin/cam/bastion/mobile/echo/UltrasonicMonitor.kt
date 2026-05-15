package cam.bastion.mobile.echo

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Ultrasonic beacon detector.
 *
 * Records mono 48 kHz PCM_16, runs a windowed real-input FFT, and exposes power
 * spectral density across the 18-22 kHz band — the band used by SilverPush /
 * Lisnr / cross-device retail-tracking ultrasonic beacons (Mavroudis et al.,
 * "On the Privacy and Security of the Ultrasound Ecosystem", PETS 2017).
 *
 * Phone mics + speakers can both transduce up to ~22 kHz; humans ~16-17 kHz
 * after age 25. This makes the band cheap to monitor and effectively invisible.
 *
 * Honesty scope: surfaces *energy* in the band. We don't claim every spike is
 * a beacon — wind, fingernails on glass, and CRT flyback can all chirp here.
 * Sustained narrowband peaks at >-50 dBFS for >1 s are the suspicious pattern.
 */
class UltrasonicMonitor {

    companion object {
        const val SAMPLE_RATE = 48_000
        const val FFT_SIZE = 4096
        const val BAND_LOW_HZ = 18_000
        const val BAND_HIGH_HZ = 22_000
        // Visual buckets across the band — pleasing bar count, ~100 Hz wide.
        const val DISPLAY_BUCKETS = 40

        val BIN_HZ = SAMPLE_RATE.toDouble() / FFT_SIZE
        val LOW_BIN = (BAND_LOW_HZ / BIN_HZ).toInt()
        val HIGH_BIN = (BAND_HIGH_HZ / BIN_HZ).toInt()
        val BAND_BIN_COUNT = HIGH_BIN - LOW_BIN
    }

    /** Snapshot of one FFT frame, in dBFS. */
    data class Frame(
        val buckets: FloatArray,    // size = DISPLAY_BUCKETS, dBFS
        val peakHz: Float,
        val peakDb: Float,
    )

    @Volatile var latest: Frame = Frame(FloatArray(DISPLAY_BUCKETS) { -120f }, 0f, -120f)
        private set

    @Volatile var running: Boolean = false
        private set

    private var record: AudioRecord? = null
    private var thread: Thread? = null

    private val window = FloatArray(FFT_SIZE).also { w ->
        for (i in 0 until FFT_SIZE) {
            // Hann
            w[i] = (0.5 * (1.0 - cos(2.0 * PI * i / (FFT_SIZE - 1)))).toFloat()
        }
    }

    fun start(): Boolean {
        if (running) return true
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) return false
        val bufBytes = max(minBuf, FFT_SIZE * 2 * 4)
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.UNPROCESSED.takeIf { sourceUsable(it) }
                    ?: MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufBytes,
            )
        } catch (_: Throwable) { return false }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release(); return false
        }
        record = rec
        running = true
        rec.startRecording()
        thread = Thread({ loop(rec) }, "BastionEchoFFT").also {
            it.priority = Thread.NORM_PRIORITY
            it.start()
        }
        return true
    }

    fun stop() {
        running = false
        try { record?.stop() } catch (_: Throwable) {}
        try { record?.release() } catch (_: Throwable) {}
        record = null
        try { thread?.join(300) } catch (_: Throwable) {}
        thread = null
    }

    private fun sourceUsable(@Suppress("UNUSED_PARAMETER") src: Int): Boolean = true

    private fun loop(rec: AudioRecord) {
        val pcm = ShortArray(FFT_SIZE)
        val real = FloatArray(FFT_SIZE)
        val imag = FloatArray(FFT_SIZE)
        val mag = FloatArray(FFT_SIZE / 2)
        while (running) {
            // Read exactly FFT_SIZE samples (blocking).
            var read = 0
            while (read < FFT_SIZE && running) {
                val n = rec.read(pcm, read, FFT_SIZE - read)
                if (n <= 0) break
                read += n
            }
            if (read < FFT_SIZE) continue
            // Window + convert to float [-1, 1).
            for (i in 0 until FFT_SIZE) {
                real[i] = (pcm[i] / 32768f) * window[i]
                imag[i] = 0f
            }
            fftRadix2(real, imag)
            // Magnitude in band only (skip the rest — cheaper).
            var peakBin = LOW_BIN
            var peakMag = 0f
            for (i in LOW_BIN until HIGH_BIN) {
                val m = sqrt(real[i] * real[i] + imag[i] * imag[i])
                mag[i] = m
                if (m > peakMag) { peakMag = m; peakBin = i }
            }
            // Bucket into DISPLAY_BUCKETS, take max within each bucket.
            val perBucket = BAND_BIN_COUNT.toFloat() / DISPLAY_BUCKETS
            val buckets = FloatArray(DISPLAY_BUCKETS)
            for (b in 0 until DISPLAY_BUCKETS) {
                val s = LOW_BIN + (b * perBucket).toInt()
                val e = (LOW_BIN + ((b + 1) * perBucket).toInt()).coerceAtMost(HIGH_BIN)
                var m = 0f
                for (i in s until e) if (mag[i] > m) m = mag[i]
                buckets[b] = magToDb(m)
            }
            latest = Frame(
                buckets = buckets,
                peakHz = (peakBin * BIN_HZ).toFloat(),
                peakDb = magToDb(peakMag),
            )
        }
    }

    private fun magToDb(m: Float): Float {
        if (m <= 1e-9f) return -120f
        // Normalize by FFT_SIZE/2 so a 0 dBFS sine maps to ~0 dB.
        val norm = m / (FFT_SIZE / 2f)
        return (20.0 * (ln(norm.toDouble()) / ln(10.0))).toFloat().coerceAtLeast(-120f)
    }

    /** Iterative radix-2 in-place FFT. FFT_SIZE must be a power of two. */
    private fun fftRadix2(re: FloatArray, im: FloatArray) {
        val n = re.size
        // Bit-reverse permutation.
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var size = 2
        while (size <= n) {
            val half = size / 2
            val tableStep = -2.0 * PI / size
            var k = 0
            while (k < n) {
                for (l in 0 until half) {
                    val ang = tableStep * l
                    val wr = cos(ang).toFloat()
                    val wi = sin(ang).toFloat()
                    val ar = re[k + l + half] * wr - im[k + l + half] * wi
                    val ai = re[k + l + half] * wi + im[k + l + half] * wr
                    re[k + l + half] = re[k + l] - ar
                    im[k + l + half] = im[k + l] - ai
                    re[k + l] += ar
                    im[k + l] += ai
                }
                k += size
            }
            size = size shl 1
        }
    }
}
