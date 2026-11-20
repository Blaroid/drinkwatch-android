package com.drinkwatch.app

import com.drinkwatch.app.data.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeModeTest {

    @Test
    fun followSystemTracksThePhone() {
        assertTrue(ThemeMode.System.prefersDark(systemInDark = true))
        assertFalse(ThemeMode.System.prefersDark(systemInDark = false))
    }

    @Test
    fun anExplicitChoiceIgnoresThePhone() {
        assertTrue(ThemeMode.Dark.prefersDark(systemInDark = false))
        assertFalse(ThemeMode.Light.prefersDark(systemInDark = true))
    }

    @Test
    fun everyChoiceRoundTripsThroughStorage() {
        ThemeMode.entries.forEach { mode ->
            assertEquals(mode, ThemeMode.fromStorage(mode.storageValue))
        }
    }

    @Test
    fun theStoredValueIsForgiving() {
        assertEquals(ThemeMode.Dark, ThemeMode.fromStorage(" Dark "))
        assertEquals(ThemeMode.Light, ThemeMode.fromStorage("LIGHT"))
    }

    @Test
    fun anUnknownOrMissingValueFollowsTheSystem() {
        assertEquals(ThemeMode.System, ThemeMode.fromStorage(null))
        assertEquals(ThemeMode.System, ThemeMode.fromStorage(""))
        assertEquals(ThemeMode.System, ThemeMode.fromStorage("sepia"))
    }
}
