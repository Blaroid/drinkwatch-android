package com.drinkwatch.app.data

import android.content.Context

/** Tiny manual DI: one Prefs and one Api per process. */
object ServiceLocator {
    @Volatile private var prefsRef: Prefs? = null
    @Volatile private var apiRef: Api? = null
    @Volatile private var updatesRef: UpdateController? = null

    fun prefs(context: Context): Prefs =
        prefsRef ?: synchronized(this) {
            prefsRef ?: Prefs(context.applicationContext).also { prefsRef = it }
        }

    fun updates(context: Context): UpdateController =
        updatesRef ?: synchronized(this) {
            updatesRef ?: UpdateController(context.applicationContext).also { updatesRef = it }
        }

    fun api(context: Context): Api =
        apiRef ?: synchronized(this) {
            apiRef ?: Api(prefs(context)).also { apiRef = it }
        }
}
