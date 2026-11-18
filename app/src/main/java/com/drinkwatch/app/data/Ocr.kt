package com.drinkwatch.app.data

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/**
 * Price-tag reading. ML Kit does the OCR on device; the app only needs the dollar
 * figure, and the user can always correct it before submitting.
 */
object Ocr {

    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    private val money = Regex("""(?:\$\s?)?(\d{1,3}(?:[.,]\d{2}))""")

    /** Words that mean "not the price you pay": was/save/rrp, and unit-price suffixes. */
    private val beforeNoise = Regex("""\b(was|save|saving|rrp|worth|less)\b""", RegexOption.IGNORE_CASE)
    private val afterUnit = Regex("""^\s*(?:/|per)\s*(?:1\s*)?(?:l|kg|100)""", RegexOption.IGNORE_CASE)

    fun readTag(bitmap: Bitmap, onResult: (priceCents: Int?, text: String) -> Unit) {
        runCatching { recognizer.process(InputImage.fromBitmap(bitmap, 0)) }
            .onSuccess { task ->
                task.addOnSuccessListener { result -> onResult(parsePrice(result.text), result.text) }
                    .addOnFailureListener { onResult(null, "") }
            }
            .onFailure { onResult(null, "") }
    }

    /**
     * Pick the most plausible shelf price out of whatever the camera saw.
     *
     * Strategy: drop unit prices and "was/save" figures, then prefer a figure
     * printed with a dollar sign and otherwise the first one on the label (shelf
     * tags lead with the sell price).
     */
    fun parsePrice(text: String): Int? {
        data class Candidate(val cents: Int, val hasDollar: Boolean, val index: Int)

        val candidates = money.findAll(text).mapNotNull { match ->
            val raw = match.groupValues[1].replace(',', '.')
            val value = raw.toDoubleOrNull() ?: return@mapNotNull null
            if (value < 0.5 || value > 200) return@mapNotNull null

            val before = text.substring((match.range.first - 14).coerceAtLeast(0), match.range.first)
            val after = text.substring(match.range.last + 1, (match.range.last + 16).coerceAtMost(text.length))
            val isNoise = beforeNoise.containsMatchIn(before) || afterUnit.containsMatchIn(after)
            if (isNoise) return@mapNotNull null

            Candidate(
                cents = Math.round(value * 100).toInt(),
                hasDollar = match.value.contains("$"),
                index = match.range.first,
            )
        }.toList()

        if (candidates.isEmpty()) return null
        val withDollar = candidates.filter { it.hasDollar }
        return (withDollar.ifEmpty { candidates }).minByOrNull { it.index }?.cents
    }
}
