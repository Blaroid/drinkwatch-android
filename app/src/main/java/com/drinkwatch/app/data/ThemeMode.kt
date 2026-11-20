package com.drinkwatch.app.data

/**
 * The appearance the user picked in Settings.
 *
 * Kept as an enum with a stable storage string so the choice survives an update
 * without depending on ordinal numbers, and resolved against the system in one
 * pure function that can be unit-tested without an Activity.
 */
enum class ThemeMode(val storageValue: String) {
    System("system"),
    Light("light"),
    Dark("dark");

    /** True when the app should paint its dark colours. */
    fun prefersDark(systemInDark: Boolean): Boolean = when (this) {
        System -> systemInDark
        Light -> false
        Dark -> true
    }

    companion object {
        /** An unreadable or older value follows the system rather than guessing. */
        fun fromStorage(value: String?): ThemeMode =
            entries.firstOrNull { it.storageValue == value?.trim()?.lowercase() } ?: System
    }
}
