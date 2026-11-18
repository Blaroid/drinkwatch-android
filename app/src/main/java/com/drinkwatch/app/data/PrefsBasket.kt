package com.drinkwatch.app.data

/** Basket contents persisted as "slug:qty" pairs so a shopping trip survives restarts. */
class BasketStorage(private val prefs: Prefs) {

    fun load(): List<BasketRequestItem> {
        val raw = prefs.basketRaw
        if (raw.isBlank()) return emptyList()
        return raw.split(',').mapNotNull { chunk ->
            val parts = chunk.split(':')
            if (parts.size != 2) return@mapNotNull null
            val qty = parts[1].toIntOrNull() ?: return@mapNotNull null
            BasketRequestItem(parts[0], qty)
        }
    }

    fun save(items: List<BasketRequestItem>) {
        prefs.basketRaw = items.joinToString(",") { it.slug + ":" + it.quantity }
    }
}
