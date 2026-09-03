package com.example.goya.speech

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Speech facade used by the rest of the app.
 *
 * Prefers the bundled offline Persian voice ([SherpaTtsEngine]); falls back to the device's
 * native TextToSpeech ([SystemTtsEngine]) only if the model is missing or fails to load, and
 * only if the device actually has a Persian voice.
 *
 * Utterances always replace whatever is currently playing, so freshly detected text is never
 * queued behind a stale sentence.
 */
class Speaker(context: Context) {

    private val offline = SherpaTtsEngine(context)
    private val system = SystemTtsEngine(context)

    @Volatile
    private var pending: String? = null

    /** The engine that should handle the next utterance. */
    private val active: TtsEngine?
        get() = when {
            offline.isReady -> offline
            system.isReady -> system
            else -> null
        }

    /** True when some engine can speak Persian. */
    val isReady: Boolean get() = active != null

    /** True when the offline bundled voice is in use (no device configuration required). */
    val isOffline: Boolean get() = offline.isReady

    val isSpeaking: Boolean get() = offline.isSpeaking || system.isSpeaking

    fun millisSinceLastSpeech(): Long =
        minOf(offline.millisSinceLastSpeech(), system.millisSinceLastSpeech())

    /**
     * Unpacks and initialises the offline voice in the background (~30 MB on first launch).
     * Call once from the activity. Anything requested before the engine is ready is spoken
     * as soon as initialisation finishes.
     */
    fun prepare(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            // An exception escaping this coroutine would take the whole app down, and model
            // loading touches native code, the filesystem and ~63 MB of assets.
            val ok = runCatching { offline.prepare() }.getOrElse { t ->
                Log.e(TAG, "offline voice init failed", t)
                false
            }
            Log.i(TAG, if (ok) "using bundled offline Persian voice" else "falling back to system TTS")
            pending?.let { text ->
                pending = null
                speakNow(text)
            }
        }
    }

    /** Speaks immediately, cancelling anything currently playing. */
    fun speakNow(text: String) {
        val payload = text.trim()
        if (payload.isEmpty()) return

        val engine = active
        if (engine == null) {
            pending = payload // spoken once an engine finishes initialising
            return
        }

        // Make sure the other engine is not still talking over this one.
        if (engine === offline) system.stop() else offline.stop()
        engine.speak(payload)
    }

    /** Cancels playback. Used when the camera is pointed away from any text. */
    fun stop() {
        offline.stop()
        system.stop()
    }

    fun shutdown() {
        offline.shutdown()
        system.shutdown()
    }

    private companion object {
        const val TAG = "Speaker"
    }
}
