package com.drinkwatch.app.data

/**
 * Server limits from schemas.py: BasketCompareIn.items is 1..12 and
 * BasketItemIn.quantity is 1..48. Anything outside them is a 422 with no
 * comparison at all.
 */
const val BASKET_MAX_ITEMS = 12
const val BASKET_MAX_QUANTITY = 48

/** A basket plus what clamping had to change, so the screen can say why. */
data class ClampedBasket(val items: List<BasketRequestItem>, val note: String? = null)

/**
 * Coerce [items] into what /v1/basket/compare accepts.
 *
 * The API refuses an over-limit basket outright and the refusal costs the whole
 * comparison, so the limits belong here rather than in a 422 the user has to read.
 * Lines past the twelfth are dropped instead of merged: the screen's list is what
 * the user built, and quietly folding a 13th drink into another line would be a
 * surprise. A quantity below 1 can only come from a damaged prefs string, so it is
 * raised rather than dropped so the rest of the basket still compares.
 */
fun clampBasket(items: List<BasketRequestItem>): ClampedBasket {
    val kept = items.take(BASKET_MAX_ITEMS)
    val clamped = kept.map { it.copy(quantity = it.quantity.coerceIn(1, BASKET_MAX_QUANTITY)) }
    val dropped = items.size - kept.size
    val squeezed = kept.indices.count { kept[it].quantity != clamped[it].quantity }
    return ClampedBasket(clamped, limitNote(dropped, squeezed))
}

/** What the clamp changed, in the user's terms; null when it changed nothing. */
private fun limitNote(dropped: Int, squeezed: Int): String? {
    val sentences = buildList {
        if (dropped > 0) {
            add(
                "Baskets hold $BASKET_MAX_ITEMS drinks — " +
                    if (dropped == 1) "1 extra was removed." else "$dropped extras were removed.",
            )
        }
        if (squeezed > 0) {
            add(
                "Quantities top out at $BASKET_MAX_QUANTITY — " +
                    if (squeezed == 1) "1 line was capped." else "$squeezed lines were capped.",
            )
        }
    }
    return sentences.takeIf { it.isNotEmpty() }?.joinToString(" ")
}

/** Basket contents persisted as "slug:qty" pairs so a shopping trip survives restarts. */
class BasketStorage(private val prefs: Prefs) {

    /**
     * The stored basket, clamped, plus the note when clamping changed it.
     *
     * A basket an older build saved past the limits would otherwise fail every
     * compare until the user deleted a drink, so the clamped form is written back:
     * the note then explains a change that really happened, instead of repeating on
     * every launch of a screen that is already showing twelve drinks.
     */
    fun load(): ClampedBasket {
        val clamped = clampBasket(parseBasket(prefs.basketRaw))
        if (clamped.note != null) write(clamped.items)
        return clamped
    }

    /**
     * Persist what the screen shows, clamped, and hand back what it became.
     *
     * The note is the only feedback the user gets for a change they did not make,
     * so it travels with the items instead of being recomputed by the caller.
     */
    fun save(items: List<BasketRequestItem>): ClampedBasket {
        val clamped = clampBasket(items)
        write(clamped.items)
        return clamped
    }

    private fun write(items: List<BasketRequestItem>) {
        prefs.basketRaw = items.joinToString(",") { it.slug + ":" + it.quantity }
    }
}

/** Read the "slug:qty" form, skipping chunks a corrupted write left behind. */
internal fun parseBasket(raw: String): List<BasketRequestItem> {
    if (raw.isBlank()) return emptyList()
    return raw.split(',').mapNotNull { chunk ->
        val parts = chunk.split(':')
        if (parts.size != 2) return@mapNotNull null
        val qty = parts[1].toIntOrNull() ?: return@mapNotNull null
        BasketRequestItem(parts[0], qty)
    }
}
