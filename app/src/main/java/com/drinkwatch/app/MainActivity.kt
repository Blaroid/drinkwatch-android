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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.drinkwatch.app.data.ServiceLocator
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
            LaunchedEffect(Unit) {
                runCatching { updates.check(silent = true) }
                // Keep the offline catalogue warm and tell the server what this
                // phone follows, so the Sunday digest knows what to summarise.
                val prefs = ServiceLocator.prefs(context)
                val api = ServiceLocator.api(context)
                val catalogue = com.drinkwatch.app.data.OfflineCatalogue(context)
                if (catalogue.isStale()) runCatching { catalogue.refresh(api) }
                runCatching {
                    prefs.token?.let {
                        api.syncWatchlist(prefs.trackedSlugs, prefs.lat, prefs.lon, prefs.radiusKm)
                    }
                }
            }
            DrinkWatchTheme {
                AppRoot()
                UpdatePromptDialog(updates)
            }
        }
    }
}
