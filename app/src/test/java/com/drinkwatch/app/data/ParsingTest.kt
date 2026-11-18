package com.drinkwatch.app.data

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
}
