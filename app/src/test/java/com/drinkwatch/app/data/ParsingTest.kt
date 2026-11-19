package com.drinkwatch.app.data

import org.junit.Assert.assertEquals
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
}
