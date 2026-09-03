package com.example.goya.speech

/**
 * A speech backend.
 *
 * Two implementations exist:
 *  - [SherpaTtsEngine]  : fully offline Persian VITS/Piper voice bundled in `assets` (preferred)
 *  - [SystemTtsEngine]  : Android's native TextToSpeech (fallback, needs a device Persian voice)
 *
 * [Speaker] picks between them at runtime.
 */
interface TtsEngine {

    /** True once the engine can actually speak Persian. */
    val isReady: Boolean

    /** True while audio is playing. */
    val isSpeaking: Boolean

    /** Milliseconds since the last utterance finished, or [Long.MAX_VALUE] if none ever did. */
    fun millisSinceLastSpeech(): Long

    /** Speaks [text], cancelling anything already playing. */
    fun speak(text: String)

    /** Cancels playback immediately. */
    fun stop()

    /** Releases all native resources. */
    fun shutdown()
}
