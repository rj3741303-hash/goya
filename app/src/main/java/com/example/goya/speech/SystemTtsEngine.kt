package com.example.goya.speech

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Fallback backend: Android's native [TextToSpeech].
 *
 * Only used when the bundled offline voice is unavailable. Android does not guarantee a Persian
 * voice, so [isReady] stays false unless fa_IR / fa / ar is genuinely installed. We deliberately
 * do NOT fall back to the device default locale: reading Persian text with an English voice
 * produces meaningless noise, which is worse than silence for this user.
 */
class SystemTtsEngine(context: Context) : TtsEngine {

    private val appContext = context.applicationContext
    private val available = AtomicBoolean(false)
    private val speaking = AtomicBoolean(false)
    private val lastFinishedAt = AtomicLong(0L)

    /** Init status as reported by the engine, or [NOT_REPORTED] before the callback fires. */
    private val pendingStatus = AtomicInteger(NOT_REPORTED)
    private val configured = AtomicBoolean(false)

    /**
     * Assigned inside [init], deliberately declared as a nullable `var`.
     *
     * This must NOT be a `val` whose initializer references itself. TextToSpeech's constructor
     * takes an init listener that has to touch this very object, and a self-referencing property
     * initializer sends Kotlin's type inference into a loop ("Type checking has run into a
     * recursive problem"), after which every member on it fails to resolve.
     */
    private var engine: TextToSpeech? = null

    init {
        // Create first, assign second, and only then configure. The init callback can fire on
        // another thread at any point, so it records the status and both paths call
        // configureIfPossible(); whichever runs last does the real work exactly once.
        val created = TextToSpeech(appContext) { status ->
            pendingStatus.set(status)
            configureIfPossible()
        }
        engine = created

        created.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                speaking.set(true)
            }

            override fun onDone(utteranceId: String?) {
                speaking.set(false)
                lastFinishedAt.set(System.currentTimeMillis())
            }

            @Deprecated("Required override, superseded by onError(String, Int)")
            override fun onError(utteranceId: String?) {
                speaking.set(false)
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                Log.w(TAG, "utterance error " + errorCode)
                speaking.set(false)
            }

            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                speaking.set(false)
            }
        })

        configureIfPossible()
    }

    private fun configureIfPossible() {
        val e = engine ?: return
        val status = pendingStatus.get()
        if (status == NOT_REPORTED) return
        if (!configured.compareAndSet(false, true)) return

        if (status != TextToSpeech.SUCCESS) {
            Log.e(TAG, "TTS init failed: " + status)
            return
        }

        val candidates = listOf(Locale("fa", "IR"), Locale("fa"), Locale("ar"))
        val locale = candidates.firstOrNull { candidate ->
            e.isLanguageAvailable(candidate) >= TextToSpeech.LANG_AVAILABLE
        }
        if (locale == null) {
            Log.w(TAG, "No Persian system voice installed")
            return
        }

        e.language = locale
        e.setSpeechRate(SPEECH_RATE)
        e.setPitch(PITCH)
        available.set(true)
        Log.i(TAG, "system TTS ready: " + locale)
    }

    override val isReady: Boolean get() = available.get()

    override val isSpeaking: Boolean
        get() = speaking.get() || (engine?.isSpeaking ?: false)

    override fun millisSinceLastSpeech(): Long {
        val finished = lastFinishedAt.get()
        return if (finished == 0L) Long.MAX_VALUE else System.currentTimeMillis() - finished
    }

    override fun speak(text: String) {
        if (!available.get()) return
        val e = engine ?: return
        speaking.set(true)
        e.speak(text, TextToSpeech.QUEUE_FLUSH, null, text.hashCode().toString())
    }

    override fun stop() {
        val e = engine
        if (e != null && e.isSpeaking) e.stop()
        speaking.set(false)
    }

    override fun shutdown() {
        available.set(false)
        val e = engine
        engine = null
        runCatching { e?.stop() }
        runCatching { e?.shutdown() }
    }

    private companion object {
        const val TAG = "SystemTtsEngine"

        /** Sentinel for "the engine has not reported an init status yet". */
        const val NOT_REPORTED = Int.MIN_VALUE

        /** Slightly slower than default; easier for an elderly listener to follow. */
        const val SPEECH_RATE = 0.88f
        const val PITCH = 1.0f
    }
}
