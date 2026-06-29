package com.businesscard.scanner.ocr

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
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

    suspend fun recognizeFile(context: Context, path: String): List<OcrLine> {
        if (path.isBlank()) return emptyList()
        val file = File(path)
        if (!file.exists()) return emptyList()
        val image = try {
            InputImage.fromFilePath(context, Uri.fromFile(file))
        } catch (e: Exception) {
            return emptyList()
        }
        return recognize(image)
    }

    suspend fun recognize(image: InputImage): List<OcrLine> = coroutineScope {
        val latinLines   = async { run(latin,    image) }
        val chineseLines = async { run(chinese,  image) }
        val japaneseLines = async { run(japanese, image) }
        merge(latinLines.await(), chineseLines.await(), japaneseLines.await())
    }

    // Latin is the base. CJK recognisers only contribute lines that contain
    // actual CJK characters, preventing their misreads of Latin text from
    // polluting the output. Geometry (heightPx, topPx) comes from whichever
    // recogniser first supplies each line.
    private fun merge(
        latin: List<OcrLine>,
        chinese: List<OcrLine>,
        japanese: List<OcrLine>
    ): List<OcrLine> {
        val seen = LinkedHashMap<String, OcrLine>()
        latin.forEach { if (it.text.isNotBlank()) seen[it.text.trim()] = it }
        (chinese.asSequence() + japanese.asSequence())
            .filter { it.text.isNotBlank() && CjkUtils.containsCjk(it.text) }
            .forEach { seen.putIfAbsent(it.text.trim(), it) }
        return seen.values.toList()
    }

    private suspend fun run(recognizer: TextRecognizer, image: InputImage): List<OcrLine> =
        suspendCancellableCoroutine { cont ->
            recognizer.process(image)
                .addOnSuccessListener { result ->
                    val lines = result.textBlocks.flatMap { block ->
                        block.lines.map { line -> line.toOcrLine() }
                    }
                    if (cont.isActive) cont.resume(lines)
                }
                .addOnFailureListener { if (cont.isActive) cont.resume(emptyList()) }
        }

    fun close() {
        latin.close()
        chinese.close()
        japanese.close()
    }
}

private fun Text.Line.toOcrLine() = OcrLine(
    text     = text,
    heightPx = boundingBox?.height() ?: 0,
    topPx    = boundingBox?.top    ?: 0
)
