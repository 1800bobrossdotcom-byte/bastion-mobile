package cam.bastion.mobile.scan

import android.annotation.SuppressLint
import android.content.Context
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.Executors

/**
 * Rear-cam shoulder-surfer detector ("Shadow Scan").
 *
 * Streams the REAR camera through ML Kit's on-device face detector. Designed
 * to be propped with the back lens facing the room behind the user — if
 * someone walks up behind you, ML Kit sees them. We never record, never save
 * a frame, never upload — we only count faces per frame. If 2+ faces persist
 * for >2 s, fire the SHOULDER_SURFER alert.
 *
 * Honesty scope: detects faces visible from the rear lens. Won't see someone
 * using a magnifier, a long-lens camera, or a hidden mirror. Won't catch
 * anyone outside the lens's field of view. Will false-positive when the
 * room legitimately has 2+ people in it.
 */
class ShadowScanner {

    enum class Alert { CLEAR, ONE_FACE, SHOULDER_SURFER, NO_FACE }

    data class State(
        val running: Boolean = false,
        val faceCount: Int = 0,
        val alert: Alert = Alert.NO_FACE,
        val lastUpdateMs: Long = 0L,
    )

    @Volatile var state: State = State()
        private set

    private val executor = Executors.newSingleThreadExecutor()
    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .setMinFaceSize(0.10f)
            .build()
    )

    private var multiFaceSinceMs: Long = 0L

    @SuppressLint("UnsafeOptInUsageError")
    fun bind(ctx: Context, owner: LifecycleOwner, previewView: PreviewView) {
        val providerFuture = ProcessCameraProvider.getInstance(ctx)
        providerFuture.addListener({
            val provider = try { providerFuture.get() } catch (_: Throwable) { return@addListener }

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            val analyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { ia ->
                    ia.setAnalyzer(executor) { proxy -> analyze(proxy) }
                }
            val selector = CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                .build()

            try {
                provider.unbindAll()
                provider.bindToLifecycle(owner, selector, preview, analyzer)
                state = state.copy(running = true)
            } catch (_: Throwable) {
                state = state.copy(running = false)
            }
        }, ContextMainExecutor(ctx))
    }

    fun unbind(ctx: Context) {
        runCatching {
            ProcessCameraProvider.getInstance(ctx).get().unbindAll()
        }
        state = State()
        multiFaceSinceMs = 0L
    }

    fun close() {
        runCatching { detector.close() }
        runCatching { executor.shutdown() }
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun analyze(proxy: ImageProxy) {
        val media = proxy.image
        if (media == null) { proxy.close(); return }
        val img = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
        detector.process(img)
            .addOnSuccessListener { faces -> onFaces(faces) }
            .addOnCompleteListener { proxy.close() }
            .alsoSwallowFailures()
    }

    private fun onFaces(faces: List<Face>) {
        val n = faces.size
        val now = System.currentTimeMillis()
        val newAlert = when {
            n >= 2 -> {
                if (multiFaceSinceMs == 0L) multiFaceSinceMs = now
                if (now - multiFaceSinceMs >= 2000L) Alert.SHOULDER_SURFER
                else Alert.ONE_FACE
            }
            n == 1 -> { multiFaceSinceMs = 0L; Alert.ONE_FACE }
            else -> { multiFaceSinceMs = 0L; Alert.NO_FACE }
        }
        state = state.copy(
            running = true,
            faceCount = n,
            alert = newAlert,
            lastUpdateMs = now,
        )
    }
}

private fun <T> Task<T>.alsoSwallowFailures(): Task<T> = this.addOnFailureListener { /* ignore */ }

/** ContextCompat.getMainExecutor needs core-ktx; cleaner to inline. */
@Suppress("FunctionName")
private fun ContextMainExecutor(ctx: Context): java.util.concurrent.Executor =
    androidx.core.content.ContextCompat.getMainExecutor(ctx)
