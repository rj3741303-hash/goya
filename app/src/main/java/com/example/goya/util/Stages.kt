package com.example.goya.util

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Finds the component that kills the process, and then routes around it.
 *
 * A native crash (SIGSEGV/SIGABRT inside libtesseract, libonnxruntime or libsherpa-onnx-jni)
 * cannot be caught: no `catch` block, no exception handler, nothing in Java runs afterwards. The
 * only thing that survives is what was already written to disk.
 *
 * So before entering a dangerous section we drop a marker file, and delete it on success. If the
 * marker is still there on the next launch, that section is exactly where the process died --
 * regardless of how violently it died.
 *
 * After [QUARANTINE_AFTER] failures a section is skipped permanently. That turns an app that
 * dies on launch into an app that starts with one feature disabled, which is both a working app
 * and a definitive diagnosis. Clearing the app's data resets everything.
 */
object Stages {

    /** Unpacking fas.traineddata and TessBaseAPI.init: Tesseract + Leptonica native code. */
    const val OCR_INIT = "ocr-init"

    /** First real OCR pass over a camera frame: setImage/getUTF8Text. */
    const val OCR_FRAME = "ocr-frame"

    /** Unpacking the voice and constructing OfflineTts: Sherpa-ONNX + onnxruntime + espeak. */
    const val TTS_INIT = "tts-init"

    /** First synthesis and AudioTrack playback. */
    const val TTS_SPEAK = "tts-speak"

    private const val TAG = "Stages"
    private const val DIR = "stages"
    private const val QUARANTINE_AFTER = 2

    @Volatile
    private var root: File? = null

    /**
     * Call once, early in onCreate, before any engine is constructed. Reports which sections were
     * in flight when the previous process died and bumps their failure counters.
     */
    fun install(context: Context) {
        val appContext = context.applicationContext
        val dir = File(appContext.filesDir, DIR).apply { mkdirs() }
        root = dir

        val crashed = dir.listFiles { f -> f.name.endsWith(ACTIVE) }
            ?.map { it.name.removeSuffix(ACTIVE) }
            .orEmpty()

        crashed.forEach { stage ->
            val count = failures(stage) + 1
            runCatching { counterFile(stage)?.writeText(count.toString()) }
            runCatching { activeFile(stage)?.delete() }
            Log.w(TAG, "previous run died during '$stage' (failure #$count)")
        }

        CrashLog.append(appContext, summary(crashed))
    }

    /** True when this section has failed enough times that it should no longer be attempted. */
    fun isQuarantined(stage: String): Boolean = failures(stage) >= QUARANTINE_AFTER

    /**
     * Runs [body] inside a marked section.
     *
     * If the section is quarantined, [body] is never called and [fallback] is returned instead.
     * The marker is removed when [body] returns or throws -- only an outright process death
     * leaves it behind, which is precisely the signal we want.
     */
    fun <T> guard(stage: String, fallback: T, body: () -> T): T {
        if (isQuarantined(stage)) {
            Log.w(TAG, "skipping quarantined stage '$stage'")
            return fallback
        }
        begin(stage)
        return try {
            body()
        } finally {
            end(stage)
        }
    }

    fun begin(stage: String) {
        runCatching { activeFile(stage)?.writeText(stage) }
    }

    fun end(stage: String) {
        runCatching { activeFile(stage)?.delete() }
    }

    private fun failures(stage: String): Int =
        runCatching { counterFile(stage)?.readText()?.trim()?.toIntOrNull() ?: 0 }.getOrDefault(0)

    private fun activeFile(stage: String): File? = root?.let { File(it, "$stage$ACTIVE") }

    private fun counterFile(stage: String): File? = root?.let { File(it, "$stage.failures") }

    private fun summary(crashedStages: List<String>): String = buildString {
        appendLine("----- subsystem health -----")
        if (crashedStages.isEmpty()) {
            appendLine("previous run: no section was mid-flight at exit")
        } else {
            appendLine("DIED DURING: ${crashedStages.joinToString()}")
        }
        listOf(OCR_INIT, OCR_FRAME, TTS_INIT, TTS_SPEAK).forEach { stage ->
            val count = failures(stage)
            val state = when {
                isQuarantined(stage) -> "DISABLED"
                count > 0 -> "suspect"
                else -> "ok"
            }
            appendLine("  $stage: $state (failures: $count)")
        }
    }

    private const val ACTIVE = ".active"
}
