package com.drinkwatch.app.data

/** Everything the app reads from the DrinkWatch API, as plain immutable models. */

data class Store(
    val id: Int,
    val name: String,
    val suburb: String?,
    val postcode: String?,
    val lat: Double?,
    val lon: Double?,
    val chainCode: String,
    val chainName: String,
    val chainKind: String,
    val distanceKm: Double?,
) {
    fun label(): String = buildString {
        append(name)
        suburb?.takeIf { it.isNotBlank() && !name.contains(it, ignoreCase = true) }?.let { append(" · ").append(it) }
        distanceKm?.let { append(" · ").append(Price.km(it)) }
    }
}

data class Product(
    val id: Int,
    val slug: String,
    val brand: String,
    val name: String,
    val variant: String?,
    val sizeMl: Int?,
    val packCount: Int,
    val barcode: String?,
    val category: String,
) {
    /**
     * Human label: "Monster Ultra Zero 500ml x4".
     *
     * Defensive about older rows: if the stored name still contains the brand or
     * the size (early scrapes saved raw retailer titles), do not repeat them.
     */
    fun label(): String = buildString {
        val brandText = brand.trim()
        val nameText = name.trim()
        val lowerName = nameText.lowercase()
        val lowerBrand = brandText.lowercase()

        if (brandText.isNotEmpty()) append(brandText)

        val withoutBrand = when {
            lowerBrand.isNotEmpty() && lowerName.startsWith(lowerBrand) ->
                nameText.substring(brandText.length).trim().trimStart('-', ':', ',')
            else -> nameText
        }
        val cleanName = withoutBrand
            .replace(Regex("(?i)\\benergy\\s+drink\\b"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (cleanName.isNotEmpty() && !cleanName.equals(brandText, ignoreCase = true)) {
            if (isNotEmpty()) append(' ')
            append(cleanName)
        }

        variant?.takeIf { it.isNotBlank() && !cleanName.contains(it, ignoreCase = true) }?.let {
            append(' ').append(it)
        }

        val size = sizeMl
        if (size != null && !lowerName.contains(size.toString() + "ml")) {
            if (isNotEmpty()) append(' ')
            append(size).append("ml")
            if (packCount > 1) append(" x").append(packCount)
        }
    }
}

data class DealStats(val median90dCents: Int?, val min90dCents: Int?, val deal: String)

data class Offer(
    val storeId: Int,
    val storeName: String,
    val chainCode: String,
    val chainName: String,
    val chainKind: String,
    val suburb: String?,
    val distanceKm: Double?,
    val priceCents: Int,
    val wasPriceCents: Int?,
    val perCanCents: Int?,
    val perLitreCents: Int?,
    val promoKind: String,
    val promoQty: Int?,
    val promoTotalCents: Int?,
    val inStock: Boolean?,
    val source: String,
    val priceScope: String,
    val ageHours: Double,
    val stats: DealStats?,
)

data class ProductPrices(val product: Product, val offers: List<Offer>)

data class WidgetItem(
    val slug: String,
    val label: String,
    val priceCents: Int,
    val perCanCents: Int?,
    val store: String,
    val chain: String,
    val suburb: String?,
    val distanceKm: Double?,
    val promo: String,
    val source: String,
    val ageHours: Double,
    val suggested: Boolean = false,
)

data class BasketRequestItem(val slug: String, val quantity: Int, val label: String? = null)

data class BasketLine(
    val requested: String,
    val quantity: Int,
    val productSlug: String?,
    val label: String?,
    val units: Int,
    val costCents: Int,
    val breakdown: List<String>,
)

data class BasketStore(
    val storeId: Int,
    val storeName: String,
    val chainCode: String,
    val suburb: String?,
    /** Straight-line distance from the origin, what the store list shows. */
    val distanceKm: Double,
    /** Round-trip road distance, the distance the fuel figure below was priced on. */
    val driveDistanceKm: Double?,
    /** Items only, see [cost] for the basis the server ranks on. */
    val totalCents: Int,
    val fuelCostCents: Int?,
    val totalWithFuelCents: Int?,
    val complete: Boolean,
    val items: List<BasketLine>,
)

data class BasketResult(
    val radiusKm: Double,
    val stores: List<BasketStore>,
    val multiStoreItemsCents: Int?,
    val multiStoreFuelCostCents: Int?,
    val multiStoreTotalCents: Int?,
    val multiStorePlan: List<BasketPlanLine>,
    val savingsVsWorstCents: Int?,
)

data class BasketPlanLine(
    val requested: String,
    val quantity: Int,
    val storeName: String,
    val chainCode: String,
    val costCents: Int,
)

data class BudgetItem(
    val slug: String,
    val label: String,
    val storeName: String,
    val chainCode: String,
    val units: Int,
    val costCents: Int,
    val caffeineMg: Int,
)

data class MaxCaffeine(
    val budgetCents: Int,
    val mode: String,
    val spentCents: Int,
    val leftoverCents: Int,
    val totalCaffeineMg: Int,
    val totalMl: Int,
    val items: List<BudgetItem>,
    val note: String,
    val dailyLimitMg: Int,
    val exceedsDailyLimit: Boolean,
    val storesUsed: Int,
)

data class RouteStop(
    val storeId: Int,
    val storeName: String,
    val chainCode: String,
    val suburb: String?,
    val lat: Double?,
    val lon: Double?,
    val subtotalCents: Int,
    val distanceFromOriginKm: Double?,
)

data class RoutePlan(
    val stops: List<RouteStop>,
    val itemsTotalCents: Int,
    val travelKm: Double,
    val fuelPriceCentsPerL: Int?,
    val fuelCostCents: Int,
    val totalCostCents: Int,
    val singleStoreBaselineCents: Int?,
    val netSavingCents: Int?,
    val fuelSource: String?,
)

data class BarcodeHit(
    val found: Boolean,
    val barcode: String,
    val normalised: String?,
    val checksumOk: Boolean,
    val product: Product?,
    val suggestedName: String?,
    val suggestedBrand: String?,
    val pendingHint: String?,
)

/**
 * A stored alert. Both triggers are optional: an absolute ceiling and/or a drop
 * below the chain's 30-day median. Lat/lon/radius_km are the location filter the
 * server applies, and are all present or all null.
 */
data class PriceAlert(
    val id: Int,
    val productId: Int?,
    val familySlug: String?,
    val chainCode: String?,
    val storeId: Int?,
    val maxPriceCents: Int?,
    val minDropPercent: Int?,
    val lat: Double?,
    val lon: Double?,
    val radiusKm: Double?,
    val isActive: Boolean,
    val createdAt: String,
) {
    fun summary(): String = Alerts.summary(maxPriceCents, minDropPercent, chainCode, radiusKm)
}

/** One alert to create for a product, as POSTed to /v1/alerts. */
data class AlertRequest(
    val productSlug: String,
    val maxPriceCents: Int? = null,
    val minDropPercent: Int? = null,
    val lat: Double? = null,
    val lon: Double? = null,
    val radiusKm: Double? = null,
)

data class AppNotification(
    val id: Int,
    val kind: String,
    val title: String,
    val body: String,
    val readAt: String?,
    val createdAt: String,
)

data class CaffeineEntry(val id: Int, val label: String?, val qty: Int, val mg: Int, val consumedAt: String)

data class CaffeineToday(
    val date: String,
    val totalMg: Int,
    val limitMg: Int,
    val remainingMg: Int,
    val entries: List<CaffeineEntry>,
)
