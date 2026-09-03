package com.example.goya.ocr

import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs [engine] on at most one frame at a time, no more often than [intervalMs].
 *
 * Frames that arrive while a recognition is in flight (or inside the throttle window) are closed
 * immediately and dropped, so the live preview never stutters and memory stays flat. Combine with
 * ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST to always work on the freshest frame.
 *
 * Also reports average frame brightness, which drives automatic torch control in Coach.
 */
class OcrAnalyzer(
    private val engine: OcrEngine,
    private val scope: CoroutineScope,
    private val intervalMs: Long = 450L,
    private val onResult: (text: String, luma: Int) -> Unit
) : ImageAnalysis.Analyzer {

    private val busy = AtomicBoolean(false)

    @Volatile
    private var lastRunAt = 0L

    override fun analyze(image: ImageProxy) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastRunAt < intervalMs || !busy.compareAndSet(false, true)) {
            image.close()
            return
        }
        lastRunAt = now

        // Sampled synchronously: the buffer is only valid until the image is closed.
        val luma = averageLuma(image)

        scope.launch {
            try {
                onResult(engine.recognize(image), luma)
            } catch (t: Throwable) {
                Log.w(TAG, "recognition failed", t)
                onResult("", luma) // treat as "no text" so the gate can cancel speech
            } finally {
                image.close()
                busy.set(false)
            }
        }
    }

    /**
     * Mean value of the Y plane, sampled at ~2000 points so the cost stays negligible.
     * @return 0..255, or -1 when the frame format has no luminance plane.
     */
    private fun averageLuma(image: ImageProxy): Int = try {
        val buffer = image.planes[0].buffer
        buffer.rewind()
        val size = buffer.remaining()
        if (size <= 0) {
            -1
        } else {
            val step = (size / SAMPLE_POINTS).coerceAtLeast(1)
            var sum = 0L
            var count = 0
            var i = 0
            while (i < size) {
                sum += buffer.get(i).toInt() and 0xFF
                count++
                i += step
            }
            buffer.rewind()
            if (count == 0) -1 else (sum / count).toInt()
        }
    } catch (t: Throwable) {
        -1
    }

    private companion object {
        const val TAG = "OcrAnalyzer"
        const val SAMPLE_POINTS = 2048
    }
}
