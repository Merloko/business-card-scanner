package com.businesscard.scanner.ocr

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.io.File
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

class OcrHelper {
    private val latin = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val chinese = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    private val japanese = TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())

    suspend fun recognizeFile(context: Context, path: String): String {
        if (path.isBlank()) return ""
        val file = File(path)
        if (!file.exists()) return ""
        val image = try {
            InputImage.fromFilePath(context, Uri.fromFile(file))
        } catch (e: Exception) {
            return ""
        }
        return recognize(image)
    }

    suspend fun recognize(image: InputImage): String = coroutineScope {
        val latinText = async { run(latin, image) }
        val chineseText = async { run(chinese, image) }
        val japaneseText = async { run(japanese, image) }
        merge(latinText.await(), chineseText.await(), japaneseText.await())
    }

    // Latin is the base. CJK recognizers only contribute lines that contain
    // actual CJK characters, preventing their misreads of Latin text from
    // polluting the output.
    private fun merge(latin: String, chinese: String, japanese: String): String {
        val seen = LinkedHashSet<String>()
        latin.lines().mapTo(seen) { it.trim() }
        seen.removeIf { it.isBlank() }
        (chinese.lines().asSequence() + japanese.lines().asSequence())
            .map { it.trim() }
            .filter { it.isNotBlank() && CjkUtils.containsCjk(it) }
            .forEach { seen.add(it) }
        return seen.joinToString("\n")
    }

    private suspend fun run(recognizer: TextRecognizer, image: InputImage): String =
        suspendCancellableCoroutine { cont ->
            recognizer.process(image)
                .addOnSuccessListener { if (cont.isActive) cont.resume(it.text) }
                .addOnFailureListener { if (cont.isActive) cont.resume("") }
        }

    fun close() {
        latin.close()
        chinese.close()
        japanese.close()
    }
}
