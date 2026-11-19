package com.drinkwatch.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CoordinatesTest {

    @Test
    fun `accepts coordinates at the edge of the range`() {
        assertEquals(90.0, Coordinates.parseLatitude("90")!!, 0.0)
        assertEquals(-90.0, Coordinates.parseLatitude("-90")!!, 0.0)
        assertEquals(180.0, Coordinates.parseLongitude("180")!!, 0.0)
        assertEquals(-180.0, Coordinates.parseLongitude("-180")!!, 0.0)
    }

    @Test
    fun `rejects a coordinate beyond its own limit`() {
        assertNull(Coordinates.parseLatitude("90.0001"))
        assertNull(Coordinates.parseLatitude("-91"))
        assertNull(Coordinates.parseLongitude("180.5"))
        assertNull(Coordinates.parseLongitude("-181"))
        // A longitude is not a latitude just because it is a valid longitude.
        assertEquals(100.0, Coordinates.parseLongitude("100")!!, 0.0)
        assertNull(Coordinates.parseLatitude("100"))
    }

    @Test
    fun `accepts a comma decimal separator`() {
        assertEquals(-31.9523, Coordinates.parseLatitude(" -31,9523 ")!!, 0.0)
        assertEquals(115.8613, Coordinates.parseLongitude("115,8613")!!, 0.0)
    }

    @Test
    fun `rejects text that is not a finite number`() {
        assertNull(Coordinates.parseLatitude(""))
        assertNull(Coordinates.parseLatitude("Perth"))
        assertNull(Coordinates.parseLatitude("NaN"))
        assertNull(Coordinates.parseLatitude("Infinity"))
        assertNull(Coordinates.parseLongitude("-Infinity"))
    }
}
