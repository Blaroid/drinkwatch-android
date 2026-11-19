package com.drinkwatch.app.data

import java.util.Locale

/**
 * Alert wording and input parsing, kept free of Android so it can be unit tested.
 *
 * The server accepts a price ceiling, a relative drop, or both; either one is enough
 * to fire, which is why the summary spells out whichever triggers an alert has.
 */
object Alerts {

    /** The server rejects min_drop_percent outside 1..90. */
    const val MIN_DROP_PERCENT = 1
    const val MAX_DROP_PERCENT = 90

    /** "under $3.00 or any drop of 20%+ · at clw · within 15 km" */
    fun summary(
        maxPriceCents: Int?,
        minDropPercent: Int?,
        chainCode: String?,
        radiusKm: Double?,
    ): String = listOfNotNull(
        trigger(maxPriceCents, minDropPercent),
        chainCode?.takeIf { it.isNotBlank() }?.let { "at " + it },
        radiusKm?.let { radiusLabel(it) },
    ).joinToString(" · ")

    private fun trigger(maxPriceCents: Int?, minDropPercent: Int?): String? = when {
        maxPriceCents != null && minDropPercent != null ->
            "under " + Price.aud(maxPriceCents) + " or any drop of " + minDropPercent + "%+"
        maxPriceCents != null -> "under " + Price.aud(maxPriceCents)
        minDropPercent != null -> "any drop of " + minDropPercent + "%+"
        else -> null
    }

    /** "within 15 km"; whole kilometres lose the trailing ".0" that radii come back with. */
    fun radiusLabel(radiusKm: Double): String {
        val whole = radiusKm.toInt()
        val text = if (radiusKm == whole.toDouble()) whole.toString()
        else String.format(Locale.US, "%.1f", radiusKm)
        return "within " + text + " km"
    }

    /**
     * Drop percentage from a text field, or null when it is not a number the server
     * would accept. Out-of-range input returns null rather than being clamped so the
     * dialog can refuse to submit an alert that means something else.
     */
    fun parseDropPercent(input: String): Int? {
        val digits = input.trim().removeSuffix("%").trim()
        if (digits.isEmpty()) return null
        val value = digits.toIntOrNull() ?: return null
        return value.takeIf { it in MIN_DROP_PERCENT..MAX_DROP_PERCENT }
    }
}
