package com.drinkwatch.app.data

import java.util.Locale
import kotlin.math.roundToInt

/** Money and distance formatting. Prices are integer cents everywhere. */
object Price {
    fun aud(cents: Int?): String {
        if (cents == null) return "—"
        return "$" + String.format(Locale.US, "%.2f", cents / 100.0)
    }

    fun audShort(cents: Int?): String {
        if (cents == null) return "—"
        val dollars = cents / 100.0
        return if (dollars == dollars.toInt().toDouble() && dollars >= 10) {
            "$" + dollars.toInt()
        } else {
            "$" + String.format(Locale.US, "%.2f", dollars)
        }
    }

    private val commaDecimal = Regex("""^\d{1,3},\d{2}$""")

    fun parseToCents(input: String): Int? {
        var cleaned = input.trim().removePrefix("$").trim()
        if (cleaned.isEmpty()) return null
        // "2,99" is a decimal, "1,299" is a thousands separator.
        cleaned = if (commaDecimal.matches(cleaned)) cleaned.replace(',', '.') else cleaned.replace(",", "")
        val value = cleaned.toDoubleOrNull() ?: return null
        if (value <= 0 || value > 500) return null
        return (value * 100).roundToInt()
    }

    fun km(value: Double?): String =
        if (value == null) "" else String.format(Locale.US, "%.1f km", value)

    fun age(hours: Double): String = when {
        hours < 1 -> "just now"
        hours < 24 -> String.format(Locale.US, "%.0f h ago", hours)
        else -> String.format(Locale.US, "%.0f d ago", hours / 24)
    }

    fun promoLabel(kind: String, qty: Int?, totalCents: Int?): String? = when (kind) {
        "special" -> "special"
        "clearance" -> "clearance"
        "multibuy" -> if (qty != null && totalCents != null) "$qty for " + aud(totalCents) else "multibuy"
        else -> null
    }
}
