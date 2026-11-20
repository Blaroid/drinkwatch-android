package com.drinkwatch.app

import com.drinkwatch.app.data.Product
import com.drinkwatch.app.data.Variants
import org.junit.Assert.assertEquals
import org.junit.Test

class VariantsTest {

    private fun product(
        slug: String,
        sizeMl: Int?,
        packCount: Int = 1,
    ) = Product(
        id = slug.hashCode(),
        slug = slug,
        brand = "V",
        name = "Energy Drink Blue",
        variant = null,
        sizeMl = sizeMl,
        packCount = packCount,
        barcode = null,
        category = "energy",
    )

    @Test
    fun sizesComeBeforeCartonsOfTheSameSizeAndBiggerSizesAfter() {
        val chips = Variants.sorted(
            listOf(
                product("v-500", 500),
                product("v-250-x4", 250, 4),
                product("v-250", 250),
                product("v-250-x12", 250, 12),
            )
        )

        assertEquals(listOf("v-250", "v-250-x4", "v-250-x12", "v-500"), chips.map { it.slug })
    }

    @Test
    fun anUnstatedSizeGoesLast() {
        val chips = Variants.sorted(listOf(product("v-unknown", null), product("v-500", 500)))

        assertEquals(listOf("v-500", "v-unknown"), chips.map { it.slug })
    }

    @Test
    fun theSameRowIsNeverOfferedTwice() {
        val chips = Variants.sorted(listOf(product("v-250", 250), product("v-250", 250)))

        assertEquals(1, chips.size)
    }

    @Test
    fun labelsNameTheSizeAndThenTheCarton() {
        assertEquals("250ml", Variants.chipLabel(250, 1))
        assertEquals("250ml x4", Variants.chipLabel(250, 4))
        assertEquals("Size not stated", Variants.chipLabel(null, 1))
        assertEquals("Size not stated x12", Variants.chipLabel(null, 12))
    }

    @Test
    fun aProductLabelsFromItsOwnFields() {
        assertEquals("500ml x4", Variants.chipLabel(product("v-500-x4", 500, 4)))
    }
}
