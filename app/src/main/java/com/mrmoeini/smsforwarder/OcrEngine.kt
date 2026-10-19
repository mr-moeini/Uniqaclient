package com.mrmoeini.smsforwarder

import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** On-device OCR ("Vision Engine"): reads text blocks and their screen coordinates from a bitmap. */
object OcrEngine {

    /** Blocking call - run off the main thread. Returns a JSON array of {text, bounds:[l,t,r,b]}. */
    fun recognize(bitmap: Bitmap, timeoutSec: Long = 15): JSONArray {
        val out = JSONArray()
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            val image = InputImage.fromBitmap(bitmap, 0)
            val result = Tasks.await(recognizer.process(image), timeoutSec, TimeUnit.SECONDS)
            for (block in result.textBlocks) {
                for (line in block.lines) {
                    val b = line.boundingBox ?: continue
                    out.put(
                        JSONObject()
                            .put("text", line.text)
                            .put("bounds", JSONArray().put(b.left).put(b.top).put(b.right).put(b.bottom))
                    )
                }
            }
        } catch (_: Exception) {
        } finally {
            recognizer.close()
        }
        return out
    }

    /** Finds the first OCR line containing [query] and returns its center point, or null. */
    fun findTextCenter(bitmap: Bitmap, query: String): Pair<Int, Int>? {
        val lines = recognize(bitmap)
        for (i in 0 until lines.length()) {
            val o = lines.getJSONObject(i)
            if (o.getString("text").contains(query, ignoreCase = true)) {
                val b = o.getJSONArray("bounds")
                val cx = (b.getInt(0) + b.getInt(2)) / 2
                val cy = (b.getInt(1) + b.getInt(3)) / 2
                return Pair(cx, cy)
            }
        }
        return null
    }
}
