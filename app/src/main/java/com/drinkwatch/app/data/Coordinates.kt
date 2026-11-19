package com.drinkwatch.app.data

/**
 * Home coordinates are typed into text fields, so "parses as a number" is not enough:
 * a swapped sign or a stray digit puts the home position in another hemisphere or
 * another country, and every distance, alert and price estimate is measured from it.
 *
 * Returning null leaves the stored position untouched, so a half-typed or invalid
 * value never replaces the last good one. Commas are accepted because the decimal
 * key on a comma-decimal keyboard is the only one some users have.
 */
object Coordinates {

    fun parseLatitude(text: String): Double? = parse(text, limit = 90.0)

    fun parseLongitude(text: String): Double? = parse(text, limit = 180.0)

    private fun parse(text: String, limit: Double): Double? =
        text.trim()
            .replace(',', '.')
            .toDoubleOrNull()
            ?.takeIf { it.isFinite() && it >= -limit && it <= limit }
}
