package com.drinkwatch.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetSelectionTest {

    private fun drink(slug: String, cents: Int, suggested: Boolean = false) = WidgetItem(
        slug = slug,
        label = slug,
        priceCents = cents,
        perCanCents = null,
        store = "Coles",
        chain = "Coles",
        suburb = null,
        distanceKm = null,
        promo = "none",
        source = "server",
        ageHours = 1.0,
        suggested = suggested,
    )

    @Test
    fun `picks the cheapest four, not the first four requested`() {
        val items = listOf(
            drink("red-bull-473ml", 450),
            drink("mother-original-500ml", 359),
            drink("monster-green-500ml", 329),
            drink("red-bull-250ml", 299),
            drink("v-blue-500ml", 275),
        )
        assertEquals(
            listOf("v-blue-500ml", "red-bull-250ml", "monster-green-500ml", "mother-original-500ml"),
            cheapestWidgetItems(items).map { it.slug },
        )
    }

    @Test
    fun `keeps every drink when there are fewer than four`() {
        val items = listOf(drink("red-bull-250ml", 299), drink("v-blue-500ml", 275))
        assertEquals(
            listOf("v-blue-500ml", "red-bull-250ml"),
            cheapestWidgetItems(items).map { it.slug },
        )
    }

    @Test
    fun `suggestions never displace a followed drink`() {
        // The home screen asks for suggestions after the watchlist; if such a
        // payload ever reached the widget, the cheap suggestion must not appear
        // ahead of a drink the user actually follows.
        val items = listOf(
            drink("followed-1", 450),
            drink("suggestion", 199, suggested = true),
            drink("followed-2", 359),
        )
        assertEquals(
            listOf("followed-2", "followed-1"),
            cheapestWidgetItems(items).map { it.slug },
        )
    }

    @Test
    fun `a payload of only suggestions shows nothing`() {
        val items = listOf(drink("suggestion", 199, suggested = true))
        assertEquals(emptyList<WidgetItem>(), cheapestWidgetItems(items))
    }

    @Test
    fun `equal prices keep the requested order`() {
        // The sort is stable so two drinks at the same price do not swap places
        // between refreshes; the caller's order comes from the watchlist.
        val items = listOf(drink("second", 299), drink("first", 299), drink("cheap", 150))
        assertEquals(
            listOf("cheap", "second", "first"),
            cheapestWidgetItems(items).map { it.slug },
        )
    }

    @Test
    fun `limit smaller than the list still slices after sorting`() {
        val items = listOf(drink("pricey", 500), drink("cheap", 100), drink("middle", 300))
        assertEquals(listOf("cheap", "middle"), cheapestWidgetItems(items, limit = 2).map { it.slug })
        assertEquals(emptyList<WidgetItem>(), cheapestWidgetItems(items, limit = 0))
    }
}
