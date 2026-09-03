package com.example.goya.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import androidx.camera.core.ImageProxy
import com.example.goya.util.CrashLog
import com.example.goya.util.Stages
import com.googlecode.tesseract.android.TessBaseAPI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tesseract 4 backend with the Persian (`fas`) model. Fully offline.
 *
 * Requires `app/src/main/assets/tessdata/fas.traineddata`.
 *
 * The model is 4-15 MB and must be copied out of the APK before Tesseract can load it, which is
 * far too slow for the main thread. All of that work lives in [prepare]; the constructor does
 * nothing but allocate. Until [prepare] finishes, [recognize] returns an empty string, so the
 * camera runs from the first frame and OCR switches on a moment later.
 *
 * Frames below [MIN_CONFIDENCE] mean confidence are discarded so the app stays silent instead of
 * speaking gibberish.
 */
class TesseractOcrEngine(context: Context) : OcrEngine {

    private val appContext = context.applicationContext
    private val baseDir: File = File(appContext.filesDir, "tess")
    private val api = TessBaseAPI()

    @Volatile
    private var initialised = false

    @Volatile
    private var firstFrameDone = false

    /** How many recognition results have been written to the on-device log so far. */
    private val logged = AtomicInteger(0)

    /** Counts frames that produced no text, so only every Nth one is logged. */
    private val emptyFrames = AtomicInteger(0)

    override val isReady: Boolean get() = initialised
    private val preparing = AtomicBoolean(false)

    /** Unpacks the model and initialises Tesseract. Blocking work, dispatched to IO. */
    override suspend fun prepare(): Boolean = withContext(Dispatchers.IO) {
        if (initialised) return@withContext true
        if (!preparing.compareAndSet(false, true)) return@withContext initialised

        // Tesseract and Leptonica are native code that aborts the process rather than returning
        // an error when it dislikes its data files. If that has already happened twice, stop
        // trying: a camera with no reading is still an app, a crash loop is not.
        if (Stages.isQuarantined(Stages.OCR_INIT)) {
            Log.w(TAG, "OCR init disabled after repeated native crashes")
            preparing.set(false)
            return@withContext false
        }
        Stages.begin(Stages.OCR_INIT)

        try {
            baseDir.mkdirs()
            if (!copyTrainedData()) return@withContext false

            // OEM_LSTM_ONLY is mandatory here, not a preference. tessdata_best and
            // tessdata_fast ship LSTM models only; the legacy engine's data is absent. With
            // OEM_DEFAULT, Tesseract may try to load legacy tables, fail inside native code and
            // abort the whole process instead of returning an error to us.
            val ok = api.init(baseDir.absolutePath, LANG, TessBaseAPI.OEM_LSTM_ONLY)
            if (!ok) {
                Log.e(TAG, "TessBaseAPI.init failed for '$LANG'")
                return@withContext false
            }
            // PSM_SINGLE_BLOCK, never PSM_AUTO_OSD. "OSD" (orientation/script detection) needs a
            // separate osd.traineddata that we do not bundle; when it is missing libtesseract
            // does not return an error -- it aborts the whole process with SIGABRT on the first
            // analysed frame. PSM_AUTO is safe but runs a full page-layout analysis on every
            // frame, which is the single biggest OCR cost. A handheld reader frames one
            // continuous screenful of text at a time, so the single-block assumption is exactly
            // right for this app -- and it is measurably faster, which matters because the
            // user's perception of the app is dominated by how soon the reading starts.
            api.pageSegMode = TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK
            api.setVariable("preserve_interword_spaces", "1")
            initialised = true
            Log.i(TAG, "Tesseract ready ('$LANG')")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Tesseract init failed", t)
            false
        } finally {
            Stages.end(Stages.OCR_INIT)
            preparing.set(false)
        }
    }

    /** Copies assets/tessdata/<LANG>.traineddata into filesDir/tess/tessdata. */
    private fun copyTrainedData(): Boolean {
        val tessdata = File(baseDir, "tessdata").apply { mkdirs() }
        val target = File(tessdata, "$LANG.traineddata")
        if (target.exists() && target.length() > 0) return true
        return try {
            appContext.assets.open("tessdata/$LANG.traineddata").use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Missing assets/tessdata/$LANG.traineddata - OCR disabled", t)
            runCatching { target.delete() }
            false
        }
    }

    override suspend fun recognize(image: ImageProxy): String = withContext(Dispatchers.Default) {
        if (!initialised) return@withContext ""
        if (Stages.isQuarantined(Stages.OCR_FRAME)) return@withContext ""
        val raw = image.toUprightBitmap() ?: return@withContext ""

        // Tesseract's LSTM model reads large glyphs far better than small ones. CameraX hands us
        // the smallest configured analysis size, so on modest cameras the text strokes can fall
        // below what the model resolves cleanly (garbled letters, half words). Scale small frames
        // up with a native bilinear filter before recognition: it costs a few milliseconds and
        // consistently raises accuracy on anything but already-large text.
        val longSide = maxOf(raw.width, raw.height)
        val bitmap = if (longSide < MIN_LONG_SIDE) {
            val scale = MIN_LONG_SIDE.toFloat() / longSide
            val scaled = Bitmap.createScaledBitmap(
                raw,
                (raw.width * scale).toInt().coerceAtLeast(1),
                (raw.height * scale).toInt().coerceAtLeast(1),
                true // bilinear
            )
            if (scaled !== raw && !raw.isRecycled) raw.recycle()
            scaled
        } else {
            raw
        }

        // Only the first pass is marked. Recognition itself is native, and a page-layout or
        // script-data fault shows up on frame one, not frame two hundred.
        val firstPass = !firstFrameDone
        if (firstPass) Stages.begin(Stages.OCR_FRAME)

        try {
            api.setImage(bitmap)
            val text = api.utF8Text ?: ""
            val confidence = api.meanConfidence()

            // Record the first few results. "chars=0" means Tesseract genuinely saw nothing, so
            // the problem is the image (focus, light, script). A healthy chars count with a low
            // confidence means MIN_CONFIDENCE is throwing away real text. To the user both look
            // identical -- silence -- but they need opposite fixes.
            // Logging the first eight frames was a mistake: those are captured in the seconds
            // before the phone has been aimed at anything, so the log filled up with "chars=0"
            // and proved only that the ceiling has no writing on it. Every frame that produces
            // text is now recorded, plus one empty frame in every EMPTY_LOG_EVERY as a
            // reference, within a fixed budget so the file stays small.
            val worthLogging = text.isNotEmpty() ||
                emptyFrames.getAndIncrement() % EMPTY_LOG_EVERY == 0
            if (worthLogging && logged.getAndIncrement() < LOG_BUDGET) {
                CrashLog.append(
                    appContext,
                    "ocr frame: chars=${text.length} confidence=$confidence " +
                        "accepted=${confidence >= MIN_CONFIDENCE} " +
                        "size=${bitmap.width}x${bitmap.height} " +
                        "bright=${bitmap.roughBrightness()} " +
                        "sample=${text.take(40).replace("\n", " ")}"
                )
            }

            if (confidence < MIN_CONFIDENCE) "" else text
        } catch (t: Throwable) {
            Log.w(TAG, "recognition failed", t)
            ""
        } finally {
            if (firstPass) {
                firstFrameDone = true
                Stages.end(Stages.OCR_FRAME)
            }
            runCatching { api.clear() }
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    /**
     * Mean intensity of a coarse pixel sample, 0-255, or -1 if it could not be read.
     *
     * This separates two failures that look identical from the outside. A value near zero means
     * the bitmap handed to Tesseract is black, so the fault is in the camera or the frame
     * conversion. A normal value with `chars=0` means the image is fine and the camera simply
     * was not pointed at readable writing.
     */
    private fun Bitmap.roughBrightness(): Int = runCatching {
        val stepX = maxOf(1, width / 16)
        val stepY = maxOf(1, height / 16)
        var total = 0L
        var count = 0
        var y = 0
        while (y < height) {
            var x = 0
            while (x < width) {
                val pixel = getPixel(x, y)
                val r = pixel shr 16 and 0xFF
                val g = pixel shr 8 and 0xFF
                val b = pixel and 0xFF
                total += (r + g + b) / 3
                count++
                x += stepX
            }
            y += stepY
        }
        if (count == 0) -1 else (total / count).toInt()
    }.getOrDefault(-1)

    override fun close() {
        initialised = false
        runCatching { api.recycle() }
    }

    private companion object {
        const val TAG = "TesseractOcrEngine"

        /** Use "fas+ara" if you also bundle ara.traineddata for mixed material. */
        const val LANG = "fas"

        /**
         * Tesseract mean confidence (0-100) below which a frame is ignored.
         *
         * Deliberately low. This is a *mean over the whole frame*, so a page whose text is read
         * perfectly still averages down badly because of margins, shadows and edges caught in
         * the same image. At 60 real Persian sentences were being thrown away. Junk is now
         * rejected by SpeechGate on the content of the text instead, which is a far better
         * signal than an averaged confidence number.
         */
        const val MIN_CONFIDENCE = 45

        /**
         * Total number of recognition results written to the on-device log for diagnosis.
         *
         * Generous on purpose. Empty frames consume the budget at one line per
         * [EMPTY_LOG_EVERY], so a small budget could be spent entirely on empty entries before
         * the phone is ever aimed at real writing -- the same trap the old "first 8 frames"
         * logging fell into. 150 keeps the file under ~25 KB while covering a full session.
         */
        const val LOG_BUDGET = 150

        /** One frame in this many text-free frames is logged, to bound log growth. */
        const val EMPTY_LOG_EVERY = 20

        /**
         * Longest side (px) that still gets upscaled before recognition. 1280p frames are scaled
         * ~1.2x, which is enough for Tesseract to resolve small print without a big time cost.
         */
        const val MIN_LONG_SIDE = 1600
    }
}

/** Converts a CameraX frame into an upright bitmap, honouring sensor rotation. */
internal fun ImageProxy.toUprightBitmap(): Bitmap? {
    val source = runCatching { toBitmap() }.getOrNull() ?: return null
    val degrees = imageInfo.rotationDegrees
    if (degrees == 0) return source
    val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
    val rotated = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    if (rotated !== source && !source.isRecycled) source.recycle()
    return rotated
}
