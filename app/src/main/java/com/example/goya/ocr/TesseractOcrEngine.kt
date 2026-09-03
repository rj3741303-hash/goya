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
            // PSM_AUTO, never PSM_AUTO_OSD. "OSD" (orientation and script detection) requires a
            // separate osd.traineddata file that we do not bundle. When it is missing, libtesseract
            // does not return false -- it aborts the process with SIGABRT on the first frame it
            // analyses, which is an uncatchable native crash a fraction of a second after the
            // camera appears. PSM_AUTO does full page layout analysis without needing osd.
            api.pageSegMode = TessBaseAPI.PageSegMode.PSM_AUTO
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
        val bitmap = image.toUprightBitmap() ?: return@withContext ""

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
            if (logged.getAndIncrement() < LOG_FIRST_FRAMES) {
                CrashLog.append(
                    appContext,
                    "ocr frame: chars=${text.length} confidence=$confidence " +
                        "accepted=${confidence >= MIN_CONFIDENCE} " +
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

    override fun close() {
        initialised = false
        runCatching { api.recycle() }
    }

    private companion object {
        const val TAG = "TesseractOcrEngine"

        /** Use "fas+ara" if you also bundle ara.traineddata for mixed material. */
        const val LANG = "fas"

        /** Tesseract mean confidence (0-100) below which a frame is ignored. */
        const val MIN_CONFIDENCE = 60

        /** Number of recognition results written to the on-device log for diagnosis. */
        const val LOG_FIRST_FRAMES = 8
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
