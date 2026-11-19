package com.drinkwatch.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.drinkwatch.app.data.AlertGate
import com.drinkwatch.app.data.AppNotification
import com.drinkwatch.app.data.CaffeineToday
import com.drinkwatch.app.data.PriceAlert
import com.drinkwatch.app.data.Product
import com.drinkwatch.app.data.ServiceLocator
import com.drinkwatch.app.ui.components.ErrorBar
import com.drinkwatch.app.ui.components.LoadingRow
import com.drinkwatch.app.ui.components.NewAlertDialog
import com.drinkwatch.app.ui.components.PrimaryButton
import com.drinkwatch.app.ui.components.ProductPickerDialog
import com.drinkwatch.app.ui.components.SectionCard
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YouScreen(onOpenSettings: () -> Unit = {}) {
    val context = LocalContext.current
    val prefs = remember { ServiceLocator.prefs(context) }
    val api = remember { ServiceLocator.api(context) }
    val scope = rememberCoroutineScope()

    var caffeine by remember { mutableStateOf<CaffeineToday?>(null) }
    var alerts by remember { mutableStateOf<List<PriceAlert>>(emptyList()) }
    var notifications by remember { mutableStateOf<List<AppNotification>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var alertsBlocked by remember { mutableStateOf(false) }
    var alertPicker by remember { mutableStateOf(false) }
    var caffeinePicker by remember { mutableStateOf(false) }
    var otherDrink by remember { mutableStateOf(false) }
    var alertFor by remember { mutableStateOf<Product?>(null) }

    fun load() {
        // The system answers this locally and can change while the app is in the
        // background, so ask on every load rather than trusting a cached flag.
        alertsBlocked = AlertGate.isBlocked(context)
        scope.launch {
            loading = true
            error = null
            runCatching {
                api.ensureDevice()
                val today = api.caffeineToday()
                val alertList = api.alerts()
                val notes = api.notifications()
                Triple(today, alertList, notes)
            }.onSuccess { (today, alertList, notes) ->
                caffeine = today
                alerts = alertList
                notifications = notes
                // Only mark read what the worker has already pushed (id <= cursor):
                // marking a newer alert read would label it handled while it still
                // needs its phone notification. The list itself is unaffected.
                val surfaced = prefs.lastSeenNotificationId
                notes.filter { it.readAt == null && it.id <= surfaced }.take(5).forEach { note ->
                    runCatching { api.markNotificationRead(note.id) }
                }
            }.onFailure { error = it.message }
            loading = false
        }
    }

    LaunchedEffect(Unit) { load() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("You", fontWeight = FontWeight.SemiBold) },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (loading) LoadingRow()
            ErrorBar(error, onRetry = { load() })

            SectionCard(title = "Caffeine today") {
                val today = caffeine
                if (today == null) {
                    Text("—", style = MaterialTheme.typography.bodySmall)
                } else {
                    Text(
                        today.totalMg.toString() + " mg",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        today.remainingMg.toString() + " mg left of " + today.limitMg + " mg",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { (today.totalMg.toFloat() / today.limitMg.coerceAtLeast(1)).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(8.dp),
                    )
                    today.entries.takeLast(3).forEach { entry ->
                        Text(
                            (entry.label ?: "drink") + " · " + entry.mg + " mg",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = { caffeinePicker = true }, modifier = Modifier.weight(1f)) {
                        Text("I just drank one")
                    }
                    OutlinedButton(onClick = { otherDrink = true }, modifier = Modifier.weight(1f)) {
                        Text("Other drink")
                    }
                }
            }

            SectionCard(title = "Price alerts") {
                if (alerts.isEmpty()) {
                    Text(
                        "No alerts yet. Open a drink on the Prices tab and tap Alert me.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                alerts.forEach { alert ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Filled.NotificationsActive, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                alert.familySlug ?: ("product " + (alert.productId ?: 0)),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                alert.summary(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = {
                            scope.launch {
                                runCatching { api.deleteAlert(alert.id) }.onSuccess { load() }
                            }
                        }) { Icon(Icons.Filled.Delete, "Delete alert") }
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { alertPicker = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("New alert from a drink")
                }
            }

            SectionCard(title = "Notifications") {
                if (alertsBlocked) {
                    // Nothing here can reach the phone right now, and the background poll
                    // deliberately holds the cursor still while that is true, so this is
                    // the only place the user learns why the alerts went quiet.
                    Text(
                        "Alerts are off — enable notifications for DrinkWatch so price alerts can reach you.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(8.dp))
                }
                if (notifications.isEmpty()) {
                    Text(
                        "Nothing yet. Alerts land here, and as a phone notification in the background.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                notifications.take(10).forEach { note ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Text(note.title, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                        Text(
                            note.body,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(Modifier.height(70.dp))
        }
    }

    if (alertPicker) {
        ProductPickerDialog(
            title = "Which drink?",
            onDismiss = { alertPicker = false },
            onPick = {
                alertFor = it
                alertPicker = false
            },
        )
    }

    alertFor?.let { product ->
        NewAlertDialog(
            productSlug = product.slug,
            productLabel = product.label(),
            prefs = prefs,
            onDismiss = { alertFor = null },
            onSubmit = { request ->
                scope.launch {
                    runCatching { api.createAlert(request) }
                        .onSuccess { alertFor = null; load() }
                        .onFailure { error = it.message }
                }
            },
        )
    }

    if (caffeinePicker) {
        ProductPickerDialog(
            title = "What did you drink?",
            onDismiss = { caffeinePicker = false },
            onPick = { product ->
                caffeinePicker = false
                scope.launch {
                    runCatching { api.logCaffeine(product.slug, null, null) }.onSuccess { load() }
                        .onFailure { error = it.message }
                }
            },
        )
    }

    if (otherDrink) {
        OtherDrinkDialog(
            onDismiss = { otherDrink = false },
            onLog = { label, mg ->
                otherDrink = false
                scope.launch {
                    // No product slug: the server takes a free-text label and the mg the
                    // user read off the cup, so coffee, tea and one-offs can be logged.
                    runCatching { api.logCaffeine(null, label, mg) }.onSuccess { load() }
                        .onFailure { error = it.message }
                }
            },
        )
    }
}

/** Free-text caffeine entry for a drink the catalogue does not list. */
@Composable
private fun OtherDrinkDialog(onDismiss: () -> Unit, onLog: (label: String, mg: Int) -> Unit) {
    var label by remember { mutableStateOf("") }
    var mgText by remember { mutableStateOf("") }
    val mg = mgText.toIntOrNull() ?: 0
    val canLog = label.isNotBlank() && mg > 0

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Log another drink") },
        text = {
            Column {
                Text(
                    "Coffee, tea or anything not in the catalogue.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("What did you drink?") },
                    placeholder = { Text("barista flat white") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = mgText,
                    onValueChange = { typed -> mgText = typed.filter { it.isDigit() }.take(4) },
                    label = { Text("Caffeine (mg)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (mgText.isNotBlank() && mg == 0) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Enter the caffeine in milligrams (more than 0).",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (canLog) onLog(label.trim(), mg) },
                enabled = canLog,
            ) { Text("Log") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
