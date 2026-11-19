package com.drinkwatch.app.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
