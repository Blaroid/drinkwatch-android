package com.drinkwatch.app.data

/**
 * Decides whether a photograph shows a shelf price tag or a till receipt.
 *
 * Classification runs over the text ML Kit already extracted, so it costs nothing,
 * works without a connection and is unit-testable. A receipt carries bookkeeping
 * words ("subtotal", "eftpos", "gst") and several price lines; a shelf tag carries
 * one or two prices and shelf words ("ea", "was $", "per 100g").
 */
object PhotoClass {

    enum class Kind { Tag, Receipt, Unknown }

    private val receiptWords = listOf(
        "subtotal", "sub-total", "eftpos", "gst", "tendered", "tax invoice",
        "abn", "savings", "balance", "thank you", "receipt", "total", "change",
    )

    private val tagWords = listOf("ea", "each", "per 100", "/ 1l", "/1l", "was $", "now $", "special")

    private val price = Regex("""\$\s?\d{1,3}(?:[.,]\d{2})""")

    /** Lines that contain a dollar amount, which is what distinguishes a docket from a tag. */
    fun priceLines(text: String): Int = text.lines().count { price.containsMatchIn(it) }

    fun classify(text: String): Kind {
        if (text.isBlank()) return Kind.Unknown
        val body = text.lowercase()
        val prices = priceLines(body)
        val receiptHits = receiptWords.count { body.contains(it) }
        val tagHits = tagWords.count { body.contains(it) }

        // Two bookkeeping words never appear on a supermarket shelf label.
        if (receiptHits >= 2) return Kind.Receipt
        if (receiptHits == 1 && prices >= 3) return Kind.Receipt
        if (tagHits >= 1) return Kind.Tag
        if (prices in 1..2) return Kind.Tag
        return if (prices >= 3) Kind.Receipt else Kind.Unknown
    }
}
