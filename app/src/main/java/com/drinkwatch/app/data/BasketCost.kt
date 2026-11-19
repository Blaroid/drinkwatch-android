package com.drinkwatch.app.data

/**
 * One basket total split into shelf and fuel.
 *
 * The server ranks single stores on items + fuel and the split plan's total covers
 * both as well, so headlining a shelf-only number made the two cards contradict each
 * other. Every card now prints [totalCents] on that combined basis and keeps the parts
 * for the "shelf + fuel = total" line underneath.
 *
 * [fuelCents] is null when the server reported no fuel figure (an older deploy, or a
 * basket it could not price); then there is nothing honest to add and the total is only
 * what the server sent.
 */
data class BasketCost(val itemsCents: Int, val fuelCents: Int?) {
    val totalCents: Int get() = itemsCents + (fuelCents ?: 0)

    fun includesFuel(): Boolean = (fuelCents ?: 0) > 0

    /** What the headline number counts, so two cards can be compared at a glance. */
    fun basisLabel(): String = if (includesFuel()) "incl. fuel" else "shelf only"

    /** "$18.50 + $1.30 fuel = $19.80", or the total alone when there is no fuel to add. */
    fun breakdown(): String {
        val fuel = fuelCents ?: return Price.aud(totalCents)
        if (fuel <= 0) return Price.aud(totalCents)
        return Price.aud(itemsCents) + " + " + Price.aud(fuel) + " fuel = " + Price.aud(totalCents)
    }
}

/** A store's cost on the ranking basis: the items plus the fuel for the drive there. */
fun BasketStore.cost(): BasketCost {
    val fuel = fuelCostCents?.coerceAtLeast(0)
    // Trust the server's own sum when it has one; fall back to adding it up ourselves.
    val combined = totalWithFuelCents?.takeIf { it > 0 } ?: fuel?.let { totalCents + it }
    return BasketCost(
        itemsCents = totalCents,
        // If only the combined total arrived, the difference is the fuel it priced in.
        fuelCents = fuel ?: combined?.let { (it - totalCents).takeIf { extra -> extra > 0 } },
    )
}

/**
 * The split plan's cost on the same basis as the single-store card.
 *
 * `multi_store_total_cents` includes fuel only because the server ranks with
 * `include_fuel` on (the default the app never overrides), and the schema reports the
 * item and fuel parts either way, so the parts are preferred and the server's total is
 * just a fallback. That keeps both cards built by the same rule.
 */
fun BasketResult.multiStoreCost(): BasketCost? {
    val items = multiStoreItemsCents
    val fuel = multiStoreFuelCostCents?.coerceAtLeast(0)
    val total = multiStoreTotalCents
    return when {
        items != null && fuel != null -> BasketCost(items, fuel)
        items != null && total != null -> BasketCost(items, (total - items).takeIf { it > 0 } ?: 0)
        total != null -> BasketCost(total, null)
        else -> null
    }
}
