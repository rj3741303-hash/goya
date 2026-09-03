package com.example.goya.ocr

import androidx.camera.core.ImageProxy

/**
 * Pluggable OCR backend.
 *
 * Implementations are called from a single worker coroutine, one frame at a time,
 * and must NOT close the supplied [ImageProxy]: OcrAnalyzer owns its lifecycle.
 */
interface OcrEngine {

    /**
     * Performs any expensive one-time setup (unpacking models, loading native state).
     *
     * Must be called off the main thread. Backends that need no setup keep the default.
     * [recognize] returns an empty string until this completes, so the camera can start
     * immediately and OCR simply switches on a moment later.
     *
     * @return true when the engine is usable.
     */
    suspend fun prepare(): Boolean = true

    /**
     * True once [prepare] has succeeded and frames are actually being recognised.
     *
     * Reported in the on-device diagnostics log: it is the difference between "the app found no
     * text" and "the app never had a working recogniser", which look identical from outside.
     * Backends that need no setup keep the default.
     */
    val isReady: Boolean get() = true

    /** Returns recognised text, or an empty string when nothing usable was found. */
    suspend fun recognize(image: ImageProxy): String

    /** Releases native resources. */
    fun close()
}
