package com.drinkwatch.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
