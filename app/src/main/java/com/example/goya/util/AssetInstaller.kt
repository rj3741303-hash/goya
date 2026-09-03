package com.example.goya.util

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Copies a folder tree out of `assets` into the app's private files directory.
 *
 * Needed because the Sherpa-ONNX / eSpeak-NG native code requires real filesystem paths
 * (`dataDir`, model paths) and cannot read from a compressed APK asset stream.
 *
 * A `.installed` marker containing [version] prevents recopying on every launch, so the
 * ~30 MB model is unpacked exactly once, on the first run.
 */
object AssetInstaller {

    private const val TAG = "AssetInstaller"
    private const val MARKER = ".installed"

    /**
     * @param assetPath folder inside `assets`, e.g. `"tts/fa"`
     * @param version   bump this string whenever the bundled model changes
     * @return the installed directory, or null when the asset folder is missing/empty
     */
    fun install(context: Context, assetPath: String, version: String): File? {
        val target = File(context.filesDir, assetPath)
        val marker = File(target, MARKER)

        if (marker.isFile && marker.readTextOrNull() == version) {
            return target
        }

        if (!hasAsset(context, assetPath)) {
            Log.e(TAG, "assets/$assetPath is missing or empty - offline voice unavailable")
            return null
        }

        Log.i(TAG, "installing assets/$assetPath -> ${target.absolutePath}")
        target.deleteRecursively()
        target.mkdirs()

        return try {
            copyRecursively(context, assetPath, target)
            marker.writeText(version)
            target
        } catch (t: Throwable) {
            Log.e(TAG, "asset install failed", t)
            target.deleteRecursively()
            null
        }
    }

    private fun hasAsset(context: Context, assetPath: String): Boolean =
        try {
            !context.assets.list(assetPath).isNullOrEmpty()
        } catch (_: Throwable) {
            false
        }

    private fun copyRecursively(context: Context, assetPath: String, target: File) {
        val children = context.assets.list(assetPath) ?: return
        if (children.isEmpty()) {
            // Leaf: an actual file.
            target.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input ->
                target.outputStream().use { output -> input.copyTo(output, DEFAULT_BUFFER_SIZE) }
            }
            return
        }
        target.mkdirs()
        for (child in children) {
            copyRecursively(context, "$assetPath/$child", File(target, child))
        }
    }

    private fun File.readTextOrNull(): String? = try {
        readText().trim()
    } catch (_: Throwable) {
        null
    }
}
