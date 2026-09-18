package com.cinerating.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import com.cinerating.util.DiagLog
import com.cinerating.util.TitleFilters
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

data class OcrCandidate(val title: String, val bounds: Rect)

data class OcrResult(val titles: List<OcrCandidate>, val blocks: Int)

/**
 * On-device Latin OCR (ML Kit — model downloads via Play Services on first
 * use). Line-level boxes become badge anchors, scaled back to screen pixels.
 * Same TitleFilters as the tree path, so both agree on junk.
 */
object OcrReader {

    private const val TAG = "OcrReader"

    private val client: TextRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    suspend fun readTitles(
        context: Context,
        bitmap: Bitmap,
        /** captureWidth / screenWidth, e.g. 0.5 for half-res snapshots */
        downscale: Float
    ): OcrResult = withContext(Dispatchers.IO) {
        val found = LinkedHashMap<String, OcrCandidate>()
        var blocks = 0
        try {
            val image = InputImage.fromBitmap(bitmap, 0)
            val result = Tasks.await(client.process(image), 25, TimeUnit.SECONDS)
            blocks = result.textBlocks.size
            val inv = if (downscale > 0) 1f / downscale else 1f
            for (block in result.textBlocks) {
                for (line in block.lines) {
                    val cleaned = TitleFilters.cleanTitle(line.text?.trim().orEmpty())
                    if (cleaned.isNullOrBlank()) continue
                    if (!TitleFilters.isLikelyMovieTitle(cleaned)) continue
                    val box = line.boundingBox ?: block.boundingBox ?: continue
                    if (box.width() <= 4 || box.height() <= 4) continue
                    val scaled = Rect(
                        (box.left * inv).toInt(),
                        (box.top * inv).toInt(),
                        (box.right * inv).toInt(),
                        (box.bottom * inv).toInt()
                    )
                    found.putIfAbsent(TitleFilters.keyOf(cleaned), OcrCandidate(cleaned, scaled))
                    if (found.size >= 12) break
                }
                if (found.size >= 12) break
            }
            DiagLog.log(
                context,
                "ocr: blocks=${result.textBlocks.size} titles=${found.size}"
            )
        } catch (e: Exception) {
            // First run downloads the model; offline/device w/o Play Services lands here.
            DiagLog.log(context, "ocr: failed (${e.message}), will retry later")
        }
        OcrResult(found.values.toList(), blocks)
    }
}
