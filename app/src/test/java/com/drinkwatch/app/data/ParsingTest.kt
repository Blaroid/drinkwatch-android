package com.drinkwatch.app.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ParsingTest {

    @Test
    fun `price parses dollars and cents`() {
        assertEquals(350, Price.parseToCents("3.50"))
        assertEquals(350, Price.parseToCents("$3.50"))
        assertEquals(350, Price.parseToCents(" 3.5 "))
        assertEquals(1200, Price.parseToCents("12"))
        assertEquals(299, Price.parseToCents("2,99"))
    }

    @Test
    fun `price rejects nonsense`() {
        assertNull(Price.parseToCents(""))
        assertNull(Price.parseToCents("abc"))
        assertNull(Price.parseToCents("0"))
        assertNull(Price.parseToCents("999"))
    }

    @Test
    fun `formats money`() {
        assertEquals("$3.50", Price.aud(350))
        assertEquals("$12", Price.audShort(1200))
        assertEquals("$8.75", Price.audShort(875))
        assertEquals("—", Price.aud(null))
    }

    @Test
    fun `ocr prefers dollar figures and the largest`() {
        val tag = "Monster Ultra 500ml\n$3.50 each\n$7.00 / 1L\nsave $1.20"
        assertEquals(350, Ocr.parsePrice(tag))
    }

    @Test
    fun `ocr handles no dollar sign`() {
        assertEquals(429, Ocr.parsePrice("V Energy 500ml\n4.29 ea"))
        assertEquals(250, Ocr.parsePrice("SPECIAL\n2.50\nWAS 3.50"))
    }

    @Test
    fun `ocr ignores implausible numbers`() {
        assertNull(Ocr.parsePrice("500ml\n32mg/100ml\n2024"))
        assertNull(Ocr.parsePrice(""))
    }

    @Test
    fun `prunes only the slug the server names`() {
        val slugs = listOf("v-blue", "monster-ultra", "red-bull-250ml")
        assertEquals(
            listOf("v-blue", "red-bull-250ml"),
            pruneStale(slugs, "unknown product slug: monster-ultra"),
        )
        assertEquals(
            listOf("monster-ultra", "red-bull-250ml"),
            pruneStale(slugs, " unknown product slug: v-blue "),
        )
    }

    @Test
    fun `prunes nothing it cannot identify`() {
        val slugs = listOf("v-blue", "monster-ultra")
        // A slug we never sent, a generic 404 and a missing detail must all leave the
        // list alone: deleting the wrong drink is worse than a default list for a run.
        assertEquals(slugs, pruneStale(slugs, "unknown product slug: something-else"))
        assertEquals(slugs, pruneStale(slugs, "Not Found"))
        assertEquals(slugs, pruneStale(slugs, null))
        assertEquals(emptyList<String>(), pruneStale(emptyList(), "unknown product slug: v-blue"))
    }

    @Test
    fun `stale note names what was dropped`() {
        assertEquals(
            "V Blue is no longer listed — removed from your drinks",
            staleNote(listOf("v-blue"), mapOf("v-blue" to "V Blue")),
        )
        assertEquals(
            "V Blue and Red Bull 250ml are no longer listed — removed from your drinks",
            staleNote(
                listOf("v-blue", "red-bull-250ml"),
                mapOf("v-blue" to "V Blue", "red-bull-250ml" to "Red Bull 250ml"),
            ),
        )
        assertEquals(
            "v blue, monster ultra and red bull are no longer listed — removed from your drinks",
            staleNote(listOf("v-blue", "monster-ultra", "red-bull"), emptyMap()),
        )
        assertNull(staleNote(emptyList(), emptyMap()))
    }

    @Test
    fun `widget age counts the payload's own age`() {
        val generatedAt = parseInstant("2026-11-19T03:54:09.332071Z")!!
        val monthLater = generatedAt + 30L * 24 * 60 * 60 * 1000
        // age_hours is frozen when the server builds the payload: 2 h then, 30 days
        // and 2 h now. A cached copy must not keep claiming "2 h ago".
        assertEquals("30 d ago", cachedAgeLabel(generatedAt, oldestAgeHours = 2.0, nowMillis = monthLater))
        assertEquals("just now", cachedAgeLabel(generatedAt, oldestAgeHours = 0.0, nowMillis = generatedAt + 60_000))
        // Nothing cached: the widget says "tap to open" instead of inventing an age.
        assertNull(cachedAgeLabel(null, oldestAgeHours = 2.0, nowMillis = monthLater))
    }

    @Test
    fun `cached widget keeps the payload timestamp`() {
        val json = """
            {"generated_at":"2026-11-19T03:54:09.332071Z","radius_km":15.0,"items":[
              {"slug":"v-blue","label":"V Blue","price_cents":329,"store":"SpudShed Waterford",
               "chain":"spudshed","promo":"special","source":"scrape","age_hours":1.7}]}
        """.trimIndent()
        val cache = Api.cachedWidget(json)
        assertEquals("V Blue", cache.items.single().label)
        assertEquals(parseInstant("2026-11-19T03:54:09.332071Z"), cache.generatedAt)
        // An empty or corrupt copy must not crash the widget.
        assertEquals(WidgetCache(emptyList(), null), Api.cachedWidget(null))
        assertNull(Api.cachedWidget("{not json").generatedAt)
    }

    @Test
    fun `parses the timestamps the api sends`() {
        assertEquals(0L, parseInstant("1970-01-01T00:00:00Z"))
        // A tz-aware variant must land on the same instant rather than be dropped.
        assertEquals(0L, parseInstant("1970-01-01T10:00:00+10:00"))
        assertNull(parseInstant(null))
        assertNull(parseInstant(""))
        assertNull(parseInstant("yesterday"))
    }

    private fun note(id: Int) = AppNotification(
        id = id,
        kind = "price_alert",
        title = "alert $id",
        body = "body $id",
        readAt = null,
        createdAt = "2026-01-01T00:00:00Z",
    )

    @Test
    fun `pending alerts post the oldest unread first, not the newest`() {
        // The API answers newest-first: id order and response order disagree.
        val all = listOf(9, 8, 7, 6, 5).map { note(it) }
        assertEquals(listOf(5, 6, 7), Notifications.pending(all, cursor = 4).map { it.id })
    }

    @Test
    fun `pending alerts ignore read_at so an in-app read cannot swallow the push`() {
        val all = listOf(note(11).copy(readAt = "2026-01-01T00:00:00Z"), note(12))
        assertEquals(listOf(11, 12), Notifications.pending(all, cursor = 10).map { it.id })
    }

    @Test
    fun `nothing is pending at or below the cursor`() {
        assertTrue(Notifications.pending(listOf(note(3), note(4)), cursor = 4).isEmpty())
    }

    @Test
    fun `cursor stops at the third-lowest of five so the rest are retried`() {
        val pending = listOf(5, 6, 7, 8, 9)
        val cursor = Notifications.advancedCursor(pending, postedIds = setOf(5, 6, 7), previous = 4)
        assertEquals(7, cursor)
        assertEquals(
            listOf(8, 9),
            Notifications.pending(pending.map { note(it) }, cursor).map { it.id },
        )
    }

    @Test
    fun `cursor does not jump over a failed post`() {
        // 7 failed but 8 posted: stay at 6 so 7 is retried (8 may repeat).
        assertEquals(6, Notifications.advancedCursor(listOf(5, 6, 7, 8), setOf(5, 6, 8), previous = 4))
    }

    @Test
    fun `cursor holds when nothing posted`() {
        assertEquals(4, Notifications.advancedCursor(listOf(5, 6), emptySet(), previous = 4))
        assertEquals(0, Notifications.advancedCursor(emptyList(), emptySet(), previous = 0))
    }
    // A trimmed /v1/basket/compare body: the two stores in the PR, fuel fields included.
    private val basketJson = """
        {
          "radius_km": 10.0,
          "stores": [
            {
              "store_id": 15,
              "store_name": "Woolworths Perth Cultural Centre",
              "chain_code": "woolworths",
              "suburb": "Perth Cultural Centre",
              "distance_km": 0.11,
              "drive_distance_km": 0.3,
              "fuel_cost_cents": 130,
              "total_cents": 1850,
              "total_with_fuel_cents": 1980,
              "complete": true,
              "items": [
                {
                  "requested": "monster-ultra-500ml",
                  "quantity": 4,
                  "product_slug": "monster-ultra-500ml-x4",
                  "label": "Monster Ultra 500ml x4",
                  "units": 4,
                  "cost_cents": 1850,
                  "breakdown": ["4 x single @ 4.63"]
                }
              ]
            },
            {
              "store_id": 256,
              "store_name": "Woolworths Highgate",
              "chain_code": "woolworths",
              "suburb": "Highgate",
              "distance_km": 1.25,
              "drive_distance_km": 3.2,
              "fuel_cost_cents": 40,
              "total_cents": 1900,
              "total_with_fuel_cents": 1940,
              "complete": true,
              "items": []
            }
          ],
          "multi_store_items_cents": 1851,
          "multi_store_fuel_cost_cents": 59,
          "multi_store_total_cents": 1910,
          "multi_store_plan": [
            {
              "requested": "monster-ultra-500ml",
              "quantity": 4,
              "store_id": 15,
              "store_name": "Woolworths Perth Cultural Centre",
              "chain_code": "woolworths",
              "distance_km": 0.11,
              "cost_cents": 1851
            }
          ],
          "savings_vs_worst_cents": 2379
        }
    """.trimIndent()

    @Test
    fun `basket parsing keeps the fuel fields`() {
        val basket = JSONObject(basketJson).toBasketResult()
        val store = basket.stores.first()
        assertEquals(1850, store.totalCents)
        assertEquals(130, store.fuelCostCents)
        assertEquals(1980, store.totalWithFuelCents)
        assertEquals(0.3, store.driveDistanceKm!!, 0.001)
        assertEquals(1851, basket.multiStoreItemsCents)
        assertEquals(59, basket.multiStoreFuelCostCents)
        assertEquals(1910, basket.multiStoreTotalCents)
        // Both cards have to land on the server's ranking basis or they contradict each other.
        assertEquals("\$19.80", Price.aud(store.cost().totalCents))
        assertEquals("\$19.10", Price.aud(basket.multiStoreCost()!!.totalCents))
    }

    @Test
    fun `basket parsing tolerates a server without the fuel fields`() {
        val json = """
            {
              "radius_km": 5.0,
              "stores": [{"store_id": 1, "store_name": "Old Store", "chain_code": "woolworths",
                          "distance_km": 1.0, "total_cents": 900, "complete": true, "items": []}],
              "multi_store_total_cents": 850,
              "multi_store_plan": []
            }
        """.trimIndent()
        val basket = JSONObject(json).toBasketResult()
        val store = basket.stores.single()
        assertNull(store.fuelCostCents)
        assertNull(store.totalWithFuelCents)
        assertNull(store.driveDistanceKm)
        assertEquals(900, store.cost().totalCents)
        assertEquals("shelf only", store.cost().basisLabel())
        assertEquals(850, basket.multiStoreCost()!!.totalCents)
    }

    private fun basketStore(totalCents: Int, fuelCents: Int?, withFuelCents: Int?) = BasketStore(
        storeId = 1,
        storeName = "Store",
        chainCode = "woolworths",
        suburb = null,
        distanceKm = 1.0,
        driveDistanceKm = 2.6,
        totalCents = totalCents,
        fuelCostCents = fuelCents,
        totalWithFuelCents = withFuelCents,
        complete = true,
        items = emptyList(),
    )

    private fun basketResult(items: Int?, fuel: Int?, total: Int?) = BasketResult(
        radiusKm = 10.0,
        stores = emptyList(),
        multiStoreItemsCents = items,
        multiStoreFuelCostCents = fuel,
        multiStoreTotalCents = total,
        multiStorePlan = emptyList(),
        savingsVsWorstCents = null,
    )

    @Test
    fun `cost spells out shelf plus fuel`() {
        val cost = basketStore(1850, 130, 1980).cost()
        assertEquals(1980, cost.totalCents)
        assertEquals("incl. fuel", cost.basisLabel())
        assertEquals("\$18.50 + \$1.30 fuel = \$19.80", cost.breakdown())
    }

    @Test
    fun `cost derives the fuel when only the combined total arrives`() {
        assertEquals(BasketCost(1850, 130), basketStore(1850, null, 1980).cost())
    }

    @Test
    fun `cost without fuel is shelf only`() {
        val cost = basketStore(1850, 0, 1850).cost()
        assertEquals(1850, cost.totalCents)
        assertEquals("shelf only", cost.basisLabel())
        assertEquals("\$18.50", cost.breakdown())
        assertEquals(1850, basketStore(1850, null, null).cost().totalCents)
    }

    @Test
    fun `split plan cost prefers the parts over the server total`() {
        assertEquals(BasketCost(1851, 59), basketResult(1851, 59, 1910).multiStoreCost())
        assertEquals(BasketCost(1851, 59), basketResult(1851, null, 1910).multiStoreCost())
        assertEquals(BasketCost(1910, null), basketResult(null, null, 1910).multiStoreCost())
        assertNull(basketResult(null, null, null).multiStoreCost())
    }

    @Test
    fun `alert summary names the triggers and the radius`() {
        assertEquals("under $3.00 · within 15 km", Alerts.summary(300, null, null, 15.0))
        assertEquals("any drop of 20%+", Alerts.summary(null, 20, null, null))
        assertEquals(
            "under $3.00 or any drop of 20%+ · at clw · within 12.5 km",
            Alerts.summary(300, 20, "clw", 12.5),
        )
        // An alert stored before location filtering existed has no radius to show.
        assertEquals("under $3.00", Alerts.summary(300, null, null, null))
    }

    @Test
    fun `alert request always carries the location trio`() {
        val fields = AlertRequest("v-energy", 250, null, -31.95, 115.86, 15.0).toFields()
        assertEquals("v-energy", fields["product_slug"])
        assertEquals(250, fields["max_price_cents"])
        assertEquals(-31.95, fields["lat"])
        assertEquals(115.86, fields["lon"])
        assertEquals(15.0, fields["radius_km"])
        assertFalse(fields.containsKey("min_drop_percent"))
    }

    @Test
    fun `alert request sends a drop-only trigger`() {
        val fields = AlertRequest("v-energy", minDropPercent = 20, lat = -31.9, lon = 115.8, radiusKm = 10.0)
            .toFields()
        assertFalse(fields.containsKey("max_price_cents"))
        assertEquals(20, fields["min_drop_percent"])
    }

    @Test
    fun `alert request never sends half a location`() {
        // The server rejects a partial lat/lon/radius_km, so drop all of it.
        val fields = AlertRequest("v-energy", 250, lat = -31.95).toFields()
        assertFalse(fields.containsKey("lat"))
        assertFalse(fields.containsKey("lon"))
        assertFalse(fields.containsKey("radius_km"))
    }

    @Test
    fun `drop percent parsing matches the server range`() {
        assertEquals(20, Alerts.parseDropPercent("20"))
        assertEquals(20, Alerts.parseDropPercent(" 20% "))
        assertNull(Alerts.parseDropPercent(""))
        assertNull(Alerts.parseDropPercent("abc"))
        assertNull(Alerts.parseDropPercent("0"))
        assertNull(Alerts.parseDropPercent("91"))
    }
}
