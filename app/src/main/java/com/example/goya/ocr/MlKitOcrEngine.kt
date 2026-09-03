package com.example.goya.ocr

import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await

/**
 * ML Kit Text Recognition v2 backend.
 *
 * IMPORTANT: ML Kit ships models for Latin, Chinese, Devanagari, Japanese and Korean script
 * only. There is NO Arabic/Persian model, so this engine cannot read Persian. It is kept as a
 * fast, zero-asset backend for Latin text and digits (prices, phone numbers, bus numbers) and
 * as a reference implementation of the [OcrEngine] contract.
 *
 * For Persian, use [TesseractOcrEngine].
 */
class MlKitOcrEngine : OcrEngine {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    @OptIn(ExperimentalGetImage::class)
    override suspend fun recognize(image: ImageProxy): String {
        val media = image.image ?: return ""
        val input = InputImage.fromMediaImage(media, image.imageInfo.rotationDegrees)
        return recognizer.process(input).await().text
    }

    override fun close() {
        recognizer.close()
    }
}
