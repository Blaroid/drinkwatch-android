package com.drinkwatch.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.drinkwatch.app.data.ServiceLocator
import com.drinkwatch.app.data.ThemeMode
import com.drinkwatch.app.ui.components.UpdatePromptDialog
import com.drinkwatch.app.ui.AppRoot
import com.drinkwatch.app.ui.theme.DrinkWatchTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val context = LocalContext.current
            val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
            LaunchedEffect(Unit) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
                ) {
                    permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
            val updates = ServiceLocator.updates(context)
            val prefs = ServiceLocator.prefs(context)
            LaunchedEffect(Unit) {
                runCatching { updates.check(silent = true) }
                // Keep the offline catalogue warm and tell the server what this
                // phone follows, so the Sunday digest knows what to summarise.
                val api = ServiceLocator.api(context)
                val catalogue = com.drinkwatch.app.data.OfflineCatalogue(context)
                if (catalogue.isStale()) runCatching { catalogue.refresh(api) }
                runCatching {
                    prefs.token?.let {
                        api.syncWatchlist(prefs.trackedSlugs, prefs.lat, prefs.lon, prefs.radiusKm)
                    }
                }
            }
            // The appearance choice is read once and held here, above the nav
            // graph: the Settings control repaints the whole app the moment it is
            // tapped, and the value is what gets persisted.
            var themeMode by remember { mutableStateOf(ThemeMode.fromStorage(prefs.themeMode)) }
            DrinkWatchTheme(darkTheme = themeMode.prefersDark(isSystemInDarkTheme())) {
                AppRoot(
                    themeMode = themeMode,
                    onThemeMode = { picked ->
                        themeMode = picked
                        prefs.themeMode = picked.storageValue
                    },
                )
                UpdatePromptDialog(updates)
            }
        }
    }
}
