package com.drinkwatch.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One day of the price history sparkline. */
data class HistoryPoint(val day: String, val priceCents: Int, val storeName: String?)

data class History(
    val days: Int,
    val points: List<HistoryPoint>,
    val medianCents: Int?,
    val minCents: Int?,
    val maxCents: Int?,
)

data class ReceiptCandidate(
    val line: String,
    val priceCents: Int?,
    val productSlug: String?,
    val label: String?,
    val score: Double,
)

/** A "best value nearby" row: cheapest price per unit for one drink. */
data class Deal(
    val slug: String,
    val label: String,
    val storeName: String,
    val chainCode: String,
    val distanceKm: Double?,
    val priceCents: Int,
    val perCanCents: Int?,
    val perLitreCents: Int?,
    val promoKind: String,
    val ageHours: Double,
)

/** Offline copy of the catalogue so a barcode can be recognised with no signal. */
class OfflineCatalogue(context: Context) {

    private val file = File(context.filesDir, "catalogue.json")

    fun barcodeLookup(code: String): String? = runCatching {
        val root = JSONObject(file.readText())
        val variants = barcodeVariants(code)
        val map = root.optJSONObject("barcodes") ?: return null
        variants.firstOrNull { map.has(it) }?.let { map.getString(it) }
    }.getOrNull()

    suspend fun refresh(api: Api): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val payload = api.catalogue()
            file.writeText(payload.toString())
            true
        }.getOrDefault(false)
    }

    /** Best-effort label for a barcode with no network, from the cached bundle. */
    fun offlineLabel(code: String): String? = runCatching {
        val root = JSONObject(file.readText())
        val slug = barcodeLookup(code) ?: return null
        val products = root.optJSONArray("products") ?: return null
        for (index in 0 until products.length()) {
            val row = products.getJSONObject(index)
            if (row.optString("slug") == slug) {
                val label = listOf(row.optString("brand"), row.optString("name"))
                    .filter { it.isNotBlank() }.joinToString(" ")
                val size = if (row.isNull("size_ml")) "" else " ${row.optInt("size_ml")}ml"
                return label + size
            }
        }
        null
    }.getOrNull()

    fun updatedAt(): Long = if (file.exists()) file.lastModified() else 0L

    fun isStale(maxAgeMs: Long = 7L * 24 * 60 * 60 * 1000): Boolean =
        System.currentTimeMillis() - updatedAt() > maxAgeMs

    companion object {
        /** Same idea as the server: EAN-13, GTIN-14 and UPC-A all describe one can. */
        fun barcodeVariants(raw: String): List<String> {
            val digits = raw.filter { it.isDigit() }
            if (digits.isEmpty()) return emptyList()
            val variants = mutableListOf(digits)
            if (digits.length == 14 && (digits[0] == '1' || digits[0] == '0')) {
                variants += digits.substring(1)
                variants += digits.substring(1).trimStart('0')
            }
            if (digits.length == 12) variants += "0$digits"
            if (digits.length == 13) variants += digits.trimStart('0')
            return variants.filter { it.length in 8..14 }.distinct()
        }
    }
}
