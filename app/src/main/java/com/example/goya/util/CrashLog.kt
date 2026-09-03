package com.example.goya.util

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * On-device diagnostics for a phone with no developer tools attached.
 *
 * Everything lands in one plain text file, readable in any file manager:
 *
 *     Android/data/com.example.goya/files/goya-log.txt
 *
 * Three sources feed it:
 *  1. [install] catches fatal Java/Kotlin exceptions.
 *  2. [reportPreviousExit] asks Android *why the previous process died*. This is the important
 *     one: it works for native crashes (SIGSEGV/SIGABRT inside libtesseract, libonnxruntime or
 *     libsherpa-onnx-jni), which kill the process outright and never reach any Java handler.
 *     That is why an empty log after a crash is itself evidence of a native fault.
 *  3. [Stages] records which subsystem was mid-initialisation when the process vanished.
 */
object CrashLog {

    private const val TAG = "CrashLog"
    private const val FILE_NAME = "goya-log.txt"

    /** Keep the file from growing without limit across many launches. */
    private const val MAX_BYTES = 256 * 1024

    /**
     * Call as the very first statement in onCreate: installs the exception handler, then writes
     * a session header and the post-mortem of the previous process death.
     */
    fun startSession(context: Context) {
        val appContext = context.applicationContext
        install(appContext)
        append(appContext, header(appContext))
        reportPreviousExit(appContext)
        exportToDownloads(appContext)
    }

    /**
     * Copies the log into the public Downloads folder.
     *
     * Necessary because Android 11 blocked `Android/data/<package>/files` from ordinary file
     * managers, so the app's own log directory is effectively unreachable on the device that
     * needs reading. Downloads is visible everywhere and needs no permission via MediaStore.
     */
    fun exportToDownloads(context: Context) {
        val appContext = context.applicationContext
        val source = logFile(appContext) ?: return
        if (!source.isFile) return

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            // Pre-scoped-storage devices would need WRITE_EXTERNAL_STORAGE; not worth a runtime
            // permission prompt in an app whose whole point is that it never asks for anything.
            return
        }

        runCatching {
            val resolver = appContext.contentResolver
            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI

            // Replace any previous copy so the user always opens the newest one.
            resolver.delete(
                collection,
                "${MediaStore.Downloads.DISPLAY_NAME} = ?",
                arrayOf(FILE_NAME)
            )

            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, FILE_NAME)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            }

            val uri = resolver.insert(collection, values) ?: return@runCatching
            resolver.openOutputStream(uri)?.use { output ->
                source.inputStream().use { input -> input.copyTo(output) }
            }
            Log.i(TAG, "log exported to Downloads/$FILE_NAME")
        }.onFailure { Log.w(TAG, "could not export log to Downloads: ${it.message}") }
    }

    fun logFile(context: Context): File? =
        context.applicationContext.getExternalFilesDir(null)?.let { File(it, FILE_NAME) }

    /** Appends a block of text to the on-device log. Never throws. */
    fun append(context: Context, text: String) {
        val target = logFile(context) ?: return
        runCatching {
            if (target.isFile && target.length() > MAX_BYTES) target.delete()
            target.appendText(text.trimEnd() + "\n\n")
        }
    }

    private fun install(appContext: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val stack = StringWriter().also { buffer ->
                    PrintWriter(buffer).use { error.printStackTrace(it) }
                }.toString()
                append(appContext, "FATAL EXCEPTION on thread '${thread.name}'\n$stack")
            }
            // Hand back to the platform so the process dies normally.
            previous?.uncaughtException(thread, error)
        }
    }

    private fun header(context: Context): String = buildString {
        appendLine("===== Goya launch ${stamp()} =====")
        appendLine("device:  ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("abis:    ${Build.SUPPORTED_ABIS.joinToString()}")
        append("64-bit:  ${Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()}")
    }

    /**
     * Asks the system for the reason the previous process died and, for native crashes and ANRs,
     * pulls the readable strings out of the tombstone so the offending `.so` is named.
     */
    private fun reportPreviousExit(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            append(context, "previous exit: unavailable (needs Android 11+)")
            return
        }

        val report = runCatching {
            val manager =
                context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val history =
                manager.getHistoricalProcessExitReasons(context.packageName, 0, 5)

            if (history.isEmpty()) {
                "previous exit: no history recorded"
            } else {
                buildString {
                    appendLine("----- previous process exits (newest first) -----")
                    history.take(3).forEach { info -> appendLine(describe(info)) }
                }
            }
        }.getOrElse { t -> "previous exit: lookup failed (${t.message})" }

        append(context, report)
    }

    private fun describe(info: ApplicationExitInfo): String = buildString {
        appendLine("time:        ${SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(info.timestamp))}")
        appendLine("reason:      ${reasonName(info.reason)} (${info.reason})")
        appendLine("status:      ${info.status}")
        appendLine("description: ${info.description ?: "-"}")

        // For a native crash this stream is a tombstone; for an ANR it is a thread dump. Either
        // way the printable strings tell us which library died.
        val trace = runCatching {
            info.traceInputStream?.use { stream -> printableStrings(stream.readBytes()) }
        }.getOrNull()

        if (!trace.isNullOrBlank()) {
            appendLine("---- trace (filtered) ----")
            appendLine(trace)
        }
    }

    private fun reasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_CRASH -> "CRASH (java)"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        ApplicationExitInfo.REASON_OTHER -> "OTHER"
        ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> "PACKAGE_STATE_CHANGE"
        ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "PACKAGE_UPDATED"
        else -> "UNKNOWN"
    }

    /**
     * Tombstones are protobuf, not text. Rather than parse them, extract printable runs and keep
     * only the lines that identify code: signal names, library names, frame markers, abort
     * messages. That is enough to point at the guilty component.
     */
    private fun printableStrings(bytes: ByteArray, limit: Int = 8_000): String {
        val interesting = listOf(
            ".so", "signal", "SIG", "abort", "Abort", "backtrace", "#0", "#1", "#2", "#3",
            "tesseract", "leptonica", "onnx", "sherpa", "espeak", "mlkit", "Fatal", "error",
            "Error", "traineddata", "osd"
        )

        val runs = mutableListOf<String>()
        val current = StringBuilder()
        for (byte in bytes) {
            val code = byte.toInt() and 0xFF
            if (code in 0x20..0x7E) {
                current.append(code.toChar())
            } else {
                if (current.length >= 6) runs += current.toString()
                current.setLength(0)
            }
        }
        if (current.length >= 6) runs += current.toString()

        return runs
            .filter { line -> interesting.any { line.contains(it) } }
            .distinct()
            .joinToString("\n")
            .take(limit)
    }

    private fun stamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

    init {
        Log.d(TAG, "diagnostics ready")
    }
}
