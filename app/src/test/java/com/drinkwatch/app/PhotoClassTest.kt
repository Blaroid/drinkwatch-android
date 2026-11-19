package com.drinkwatch.app

import com.drinkwatch.app.data.PhotoClass
import org.junit.Assert.assertEquals
import org.junit.Test

class PhotoClassTest {

    @Test
    fun receiptIsRecognised() {
        val text = """
            SPUDSHED WATERFORD
            19/11/2026 12:41
            MONSTER ULTRA 500ML        $8.75
            CELSIUS MANGO 330ML        $3.99
            SUBTOTAL                  $12.74
            GST INCLUDED               $1.16
            EFTPOS                    $12.74
            THANK YOU
        """.trimIndent()
        assertEquals(PhotoClass.Kind.Receipt, PhotoClass.classify(text))
    }

    @Test
    fun shelfTagIsRecognised() {
        val text = """
            MONSTER ENERGY ULTRA
            500ML
            $3.49
            WAS $4.49
        """.trimIndent()
        assertEquals(PhotoClass.Kind.Tag, PhotoClass.classify(text))
    }

    @Test
    fun unitPriceOnTagIsRecognised() {
        // "$6.98 / 1L" is a unit price, not a second drink.
        val text = "MONSTER ULTRA 500ML\n$3.49 EA\n$6.98 / 1L"
        assertEquals(PhotoClass.Kind.Tag, PhotoClass.classify(text))
    }

    @Test
    fun singlePriceWithNoWordsIsATag() {
        assertEquals(PhotoClass.Kind.Tag, PhotoClass.classify("$3.49"))
    }

    @Test
    fun manyPricesWithoutWordsIsAReceipt() {
        val text = "$3.49\n$8.75\n$3.99\n$12.74"
        assertEquals(PhotoClass.Kind.Receipt, PhotoClass.classify(text))
    }

    @Test
    fun emptyTextIsUnknown() {
        assertEquals(PhotoClass.Kind.Unknown, PhotoClass.classify(""))
        assertEquals(PhotoClass.Kind.Unknown, PhotoClass.classify("   \n  "))
    }

    @Test
    fun wordsWithoutPricesAreUnknown() {
        // Blurry shot: the camera read the brand but no money anywhere.
        assertEquals(PhotoClass.Kind.Unknown, PhotoClass.classify("MONSTER ENERGY"))
    }

    @Test
    fun priceLinesCountsOnlyLinesWithMoney() {
        val text = "MONSTER ULTRA 500ML\n$3.49\n$6.98 / 1L\nTHANK YOU"
        assertEquals(2, PhotoClass.priceLines(text))
    }
}
