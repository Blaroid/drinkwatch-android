package com.drinkwatch.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BasketVerdictTest {

    private fun plan(items: Int, fuel: Int, baseline: Int?, stops: Int, netSaving: Int? = null) = RoutePlan(
        stops = (1..stops).map { RouteStop(it, "Store $it", "coles", null, null, null, 0, null) },
        itemsTotalCents = items,
        travelKm = 1.0,
        fuelPriceCentsPerL = 202,
        fuelCostCents = fuel,
        totalCostCents = items + fuel,
        singleStoreBaselineCents = baseline,
        netSavingCents = netSaving,
        fuelSource = "fuelwatch",
    )

    @Test
    fun `single stop compares shelf against shelf, unlike the server net saving`() {
        // Live /v1/plan/route: $10.50 of items plus $0.17 fuel against a $10.50
        // baseline, which the server reports as net_saving_cents = -$0.17.
        val verdict = plan(items = 1050, fuel = 17, baseline = 1050, stops = 1, netSaving = -17).driveVerdict()!!
        assertTrue(verdict.isSingleStop)
        assertEquals(0, verdict.itemsSavingCents)
        // One stop saves nothing on the shelf, and it is not a loss either.
        assertFalse(verdict.surelyWorthIt)
    }

    @Test
    fun `multi stop shelf saving is only the items difference`() {
        // Live: $6.49 of items across two stores against a $6.70 baseline, $2.95 fuel.
        val verdict = plan(items = 649, fuel = 295, baseline = 670, stops = 2, netSaving = -274).driveVerdict()!!
        assertFalse(verdict.isSingleStop)
        assertEquals(21, verdict.itemsSavingCents)
        // A $0.21 saving does not cover the trip's fuel, so it is not called worth it.
        assertFalse(verdict.surelyWorthIt)
    }

    @Test
    fun `worth it only when the items beat the whole trip's fuel`() {
        val verdict = plan(items = 1500, fuel = 200, baseline = 2000, stops = 2).driveVerdict()!!
        assertEquals(500, verdict.itemsSavingCents)
        assertTrue(verdict.surelyWorthIt)
        // Breaking even is not a win: driving to one store costs fuel too.
        assertFalse(plan(items = 1800, fuel = 200, baseline = 2000, stops = 2).driveVerdict()!!.surelyWorthIt)
        // Fuel the server could not price proves nothing, so it cannot make a verdict.
        assertFalse(plan(items = 1500, fuel = 0, baseline = 2000, stops = 2).driveVerdict()!!.surelyWorthIt)
    }

    @Test
    fun `no baseline means no comparison`() {
        assertNull(plan(items = 1050, fuel = 17, baseline = null, stops = 1).driveVerdict())
    }

    private fun line(slug: String, units: Int) = BasketLine(
        requested = slug,
        quantity = 1,
        productSlug = if (units > 0) slug + "-x1" else null,
        label = if (units > 0) "Label " + slug else null,
        units = units,
        costCents = if (units > 0) 500 else 0,
        breakdown = emptyList(),
    )

    private fun store(name: String, complete: Boolean, vararg stock: Pair<String, Int>) = BasketStore(
        storeId = 1,
        storeName = name,
        chainCode = "coles",
        suburb = null,
        distanceKm = 1.0,
        driveDistanceKm = 1.3,
        totalCents = 500,
        fuelCostCents = 20,
        totalWithFuelCents = 520,
        complete = complete,
        items = stock.map { line(it.first, it.second) },
    )

    private fun result(stores: List<BasketStore>, plan: List<BasketPlanLine> = emptyList()) = BasketResult(
        radiusKm = 5.0,
        stores = stores,
        multiStoreItemsCents = null,
        multiStoreFuelCostCents = null,
        multiStoreTotalCents = null,
        multiStorePlan = plan,
        savingsVsWorstCents = null,
    )

    private fun basket() = listOf(
        BasketRequestItem("monster", 4, "Monster Ultra 500ml"),
        BasketRequestItem("flying", 2, "Flying Power Just Peachy 50ml"),
    )

    @Test
    fun `gaps name the line no store has and what the best attempt lacks`() {
        // Live shape: every store lists both lines, the one it cannot supply with units = 0.
        val basket = result(
            stores = listOf(
                store("Coles Northbridge", complete = false, "monster" to 4, "flying" to 0),
                store("Coles North Perth", complete = false, "monster" to 4, "flying" to 0),
            ),
            plan = listOf(BasketPlanLine("monster", 4, "Coles Northbridge", "coles", 500)),
        )
        val gaps = basket.gaps(basket())
        assertEquals(listOf("Flying Power Just Peachy 50ml"), gaps.nowhere)
        assertEquals("Coles Northbridge", gaps.nearestStore)
        assertEquals(listOf("Flying Power Just Peachy 50ml"), gaps.missingFromNearest)
        assertEquals(listOf("Flying Power Just Peachy 50ml"), gaps.missingFromSplit)
    }

    @Test
    fun `a line another store stocks is not reported as stocked nowhere`() {
        val basket = result(
            stores = listOf(
                store("Coles Northbridge", complete = false, "monster" to 4, "flying" to 0),
                store("Woolworths Highgate", complete = false, "monster" to 0, "flying" to 2),
            ),
        )
        val gaps = basket.gaps(basket())
        assertTrue(gaps.nowhere.isEmpty())
        assertEquals(listOf("Flying Power Just Peachy 50ml"), gaps.missingFromNearest)
        // No plan detail at all means no claim about what the split total covers.
        assertTrue(gaps.missingFromSplit.isEmpty())
    }

    @Test
    fun `a stocked basket with a full split plan leaves nothing missing`() {
        val basket = result(
            stores = listOf(
                store("Coles Northbridge", complete = true, "monster" to 4, "flying" to 2),
                store("Coles North Perth", complete = false, "monster" to 4, "flying" to 0),
            ),
            plan = listOf(
                BasketPlanLine("monster", 4, "Coles Northbridge", "coles", 500),
                BasketPlanLine("flying", 2, "Coles North Perth", "coles", 300),
            ),
        )
        val gaps = basket.gaps(basket())
        assertTrue(gaps.nowhere.isEmpty())
        assertTrue(gaps.missingFromNearest.isEmpty())
        assertTrue(gaps.missingFromSplit.isEmpty())
    }

    @Test
    fun `gaps fall back to a readable slug when the basket lost its labels`() {
        // parseBasket() drops labels on reload, so the line has to still be nameable;
        // slugs carry hyphens, so the fallback has to space them out rather than
        // print "flying-power-just-peachy-50ml" back at the user.
        val basket = result(
            stores = listOf(
                store(
                    "Coles Northbridge",
                    complete = false,
                    "monster-ultra-500ml" to 4,
                    "flying-power-just-peachy-50ml" to 0,
                ),
            ),
        )
        val gaps = basket.gaps(
            listOf(
                BasketRequestItem("monster-ultra-500ml", 4),
                BasketRequestItem("flying-power-just-peachy-50ml", 2),
            ),
        )
        assertEquals(listOf("flying power just peachy 50ml"), gaps.nowhere)
        assertEquals(listOf("flying power just peachy 50ml"), gaps.missingFromNearest)
    }
}
