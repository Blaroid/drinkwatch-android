package com.drinkwatch.app.data

/**
 * The route plan's comparison against the cheapest single store, kept like-for-like.
 *
 * `/v1/plan/route` defines `single_store_baseline_cents` as the cheapest complete
 * store's *shelf* total but `net_saving_cents` as that baseline minus the plan's items
 * *and* fuel. A one-stop plan therefore pays fuel the baseline never counted and always
 * comes out negative — live, one stop priced $10.50 of items plus $0.17 of fuel against
 * a $10.50 baseline and reported -$0.17. [itemsSavingCents] puts both sides on the shelf
 * basis, and [fuelCents] is reported beside it rather than folded into a difference the
 * baseline cannot support: the baseline store's own drive costs fuel too, which the
 * route plan never prices, so only the user can say whether the trip is worth it.
 */
data class DriveVerdict(
    val baselineCents: Int,
    val itemsCents: Int,
    val fuelCents: Int,
    val stopCount: Int,
) {
    /** The cheapest single store's shelf total minus the plan's shelf total. */
    val itemsSavingCents: Int get() = baselineCents - itemsCents

    /** One stop is the shop the single-store card already prices: nothing is split. */
    val isSingleStop: Boolean get() = stopCount <= 1

    /**
     * Safe to call "worth it" only when the items save more than the whole trip's
     * fuel. Driving to one store burns fuel as well, so the true gain can only be
     * better than this number, never worse. Unpriced fuel (0) proves nothing either
     * way, so it must not turn a shelf saving into a verdict.
     */
    val surelyWorthIt: Boolean
        get() = !isSingleStop && fuelCents > 0 && itemsSavingCents > fuelCents
}

/** Null when the server sent no baseline, because then there is nothing to compare. */
fun RoutePlan.driveVerdict(): DriveVerdict? {
    val baseline = singleStoreBaselineCents ?: return null
    return DriveVerdict(
        baselineCents = baseline,
        itemsCents = itemsTotalCents,
        fuelCents = fuelCostCents.coerceAtLeast(0),
        stopCount = stops.size,
    )
}

/**
 * Why the basket needs more than one shop, named line by line.
 *
 * A store lists every requested line and marks one it cannot supply with `units = 0`
 * (live /v1/basket/compare does exactly this for a line it has no stock of), so the
 * test for "this store has it" is `units`, not whether the line appears in `items`.
 * Stores arrive ranked, so the first one is the best attempt; [missingFromNearest] is
 * the line that pushed the basket past a single stop.
 */
data class BasketGaps(
    /** Requested lines not one store in range stocks. */
    val nowhere: List<String> = emptyList(),
    val nearestStore: String? = null,
    /** Lines the best-ranked store cannot supply. */
    val missingFromNearest: List<String> = emptyList(),
    /** Lines the split plan leaves out, so its total is not the whole basket. */
    val missingFromSplit: List<String> = emptyList(),
)

fun BasketResult.gaps(requested: List<BasketRequestItem>): BasketGaps {
    fun BasketStore.supplies(slug: String): Boolean = items.any { it.requested == slug && it.units > 0 }

    val nearest = stores.firstOrNull()
    // An empty plan means the server sent no per-line detail, not that nothing is
    // covered, so it must not be reported as dropping every line.
    val inSplit = multiStorePlan.map { it.requested }.toSet()
    return BasketGaps(
        nowhere = requested.filter { line -> stores.none { it.supplies(line.slug) } }.map { it.displayName() },
        nearestStore = nearest?.storeName,
        missingFromNearest = if (nearest == null) {
            emptyList()
        } else {
            requested.filterNot { nearest.supplies(it.slug) }.map { it.displayName() }
        },
        missingFromSplit = if (inSplit.isEmpty()) {
            emptyList()
        } else {
            requested.filterNot { it.slug in inSplit }.map { it.displayName() }
        },
    )
}

/** The basket's own label, else the slug spaced out the way Api labels a line. */
private fun BasketRequestItem.displayName(): String =
    label?.takeIf { it.isNotBlank() } ?: slug.replace('-', ' ')
