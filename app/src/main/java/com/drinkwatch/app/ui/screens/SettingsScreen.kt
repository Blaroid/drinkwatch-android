package com.drinkwatch.app.ui.screens

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.util.Locale
import com.drinkwatch.app.BuildConfig
import com.drinkwatch.app.data.Geo
import com.drinkwatch.app.data.PlaceName
import com.drinkwatch.app.data.Price
import com.drinkwatch.app.data.Product
import com.drinkwatch.app.data.ServiceLocator
import com.drinkwatch.app.data.UpdateState
import com.drinkwatch.app.ui.components.ErrorBar
import com.drinkwatch.app.ui.components.ProductPickerDialog
import com.drinkwatch.app.ui.components.SectionCard
import com.drinkwatch.app.widget.WidgetRefresh
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { ServiceLocator.prefs(context) }
    val scope = rememberCoroutineScope()

    var label by remember { mutableStateOf(prefs.homeLabel) }
    var latText by remember { mutableStateOf(String.format(Locale.ROOT, "%.4f", prefs.lat)) }
    var lonText by remember { mutableStateOf(String.format(Locale.ROOT, "%.4f", prefs.lon)) }
    var radius by remember { mutableStateOf(prefs.radiusKm.toFloat()) }
    var tracked by remember { mutableStateOf(prefs.trackedSlugs) }
    var labels by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var baseUrl by remember { mutableStateOf(prefs.baseUrl) }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var picker by remember { mutableStateOf(false) }

    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) {
            error = "Location permission denied"
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            val position = Geo.current(context)
            if (position == null) {
                error = "No location fix yet"
            } else {
                val place = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    PlaceName.resolve(context, position.first, position.second)
                }
                prefs.applyPosition(position.first, position.second, place)
                latText = String.format(Locale.ROOT, "%.4f", position.first)
                lonText = String.format(Locale.ROOT, "%.4f", position.second)
                if (label.isBlank() && !place.isNullOrBlank()) {
                    label = place
                    prefs.homeLabel = place
                }
                message = "Location updated" + (place?.let { " · " + it } ?: "")
                WidgetRefresh.refreshNow(context)
            }
        }
    }

    LaunchedEffect(Unit) {
        if (tracked.isNotEmpty()) {
            runCatching { ServiceLocator.api(context).searchProducts("energy", 200) }
                .onSuccess { products: List<Product> ->
                    labels = products.associate { it.slug to it.label() }
                }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionCard(title = "Home location") {
                Text(
                    "Prices are measured from here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it; prefs.homeLabel = it },
                    label = { Text("Label") },
                    placeholder = { Text(prefs.placeLabel.ifBlank { "Suburb or town" }) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (label.isBlank() && prefs.placeLabel.isNotBlank()) {
                    Text(
                        "Using \"" + prefs.placeLabel + "\" from your location",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = latText,
                        onValueChange = {
                            latText = it
                            it.replace(',', '.').toDoubleOrNull()?.let { value -> prefs.lat = value }
                        },
                        label = { Text("Latitude") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = lonText,
                        onValueChange = {
                            lonText = it
                            it.replace(',', '.').toDoubleOrNull()?.let { value -> prefs.lon = value }
                        },
                        label = { Text("Longitude") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { locationPermission.launch(Manifest.permission.ACCESS_COARSE_LOCATION) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.LocationOn, null, modifier = Modifier.width(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Use my location")
                }
            }

            SectionCard(title = "Search radius") {
                Text(radius.toInt().toString() + " km", style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = radius,
                    onValueChange = { radius = it },
                    onValueChangeFinished = {
                        prefs.radiusKm = radius.toInt()
                        WidgetRefresh.refreshNow(context)
                    },
                    valueRange = 1f..50f,
                    steps = 48,
                )
            }

            SectionCard(title = "Drinks you follow") {
                if (tracked.isEmpty()) {
                    Text(
                        "Empty means the app shows the most widely stocked drinks.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                tracked.forEach { slug ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            labels[slug] ?: slug,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        IconButton(onClick = {
                            prefs.removeTracked(slug)
                            tracked = prefs.trackedSlugs
                            WidgetRefresh.refreshNow(context)
                        }) { Icon(Icons.Filled.Delete, "Remove") }
                    }
                }
                Spacer(Modifier.height(6.dp))
                OutlinedButton(onClick = { picker = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Add a drink")
                }
            }

            SectionCard(title = "Device") {
                Text(
                    "Anonymous contribution token. Submissions are attributed to this device only.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    prefs.token?.take(10)?.plus("…") ?: "not registered yet",
                    style = MaterialTheme.typography.labelSmall,
                )
                Spacer(Modifier.height(6.dp))
                TextButton(onClick = {
                    prefs.token = null
                    prefs.deviceId = 0
                    message = "Device reset; a new identity will be created next time"
                }) { Text("Reset device identity") }
            }

            SectionCard(title = "Advanced") {
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it; prefs.baseUrl = it },
                    label = { Text("API base URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            SectionCard(title = "Updates") {
                val updates = remember { ServiceLocator.updates(context) }
                val updateState = updates.state
                Text(
                    "Installed: v" + updates.currentVersion + " (build " + updates.currentCode + ")",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(6.dp))

                when (updateState) {
                    is UpdateState.Checking -> Text(
                        "Checking for updates…",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    is UpdateState.UpToDate -> Text(
                        "You are on the latest version.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )

                    is UpdateState.Available -> {
                        Text(
                            "Version " + updateState.update.versionName + " is available.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        if (updateState.update.notes.isNotBlank()) {
                            Text(
                                updateState.update.notes,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = { scope.launch { updates.downloadAndInstall(updateState.update) } },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Download and install") }
                    }

                    is UpdateState.Downloading -> {
                        val progress = updateState.progress
                        if (progress == null) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                        } else {
                            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                            Text(
                                "Downloading " + (progress * 100).toInt() + "%",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }

                    is UpdateState.ReadyToInstall -> OutlinedButton(
                        onClick = { updates.install(updateState.file) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Open installer") }

                    is UpdateState.Error -> Text(
                        updateState.message,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )

                    else -> Unit
                }

                if (!updates.canInstallPackages()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Android needs permission to install the update: allow DrinkWatch to install unknown apps.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    TextButton(onClick = { updates.openInstallPermissionSettings() }) {
                        Text("Open permission settings")
                    }
                }

                Spacer(Modifier.height(6.dp))
                TextButton(onClick = { scope.launch { updates.check(silent = false) } }) {
                    Text("Check for updates")
                }
            }

            SectionCard(title = "About") {
                Text("Version " + BuildConfig.VERSION_NAME, style = MaterialTheme.typography.bodySmall)
                Text(
                    "Store locations from OpenStreetMap. Fuel prices from FuelWatch WA. " +
                        "Supermarket prices are collected from public product pages and user reports.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            message?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) }
            ErrorBar(error)
            Spacer(Modifier.height(70.dp))
        }
    }

    if (picker) {
        ProductPickerDialog(
            title = "Follow a drink",
            onDismiss = { picker = false },
            onPick = { product ->
                prefs.addTracked(product.slug)
                tracked = prefs.trackedSlugs
                labels = labels + (product.slug to product.label())
                picker = false
                WidgetRefresh.refreshNow(context)
            },
        )
    }
}
