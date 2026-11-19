package com.drinkwatch.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.drinkwatch.app.data.Api
import com.drinkwatch.app.data.Prefs
import com.drinkwatch.app.data.Price
import com.drinkwatch.app.data.Product
import com.drinkwatch.app.data.ServiceLocator
import com.drinkwatch.app.data.Store
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The "what did you pay?" popup for a can we already recognise.
 *
 * Scanning a barcode that resolves to a known drink jumps straight here: the
 * drink is filled in, the price field takes focus, the store is one tap away, and
 * submitting writes the sighting. No inline form, no scrolling to find it.
 */
@Composable
fun QuickPriceDialog(
    product: Product,
    barcode: String,
    api: Api,
    prefs: Prefs,
    onDismiss: () -> Unit,
    onSaved: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var priceText by remember(product.slug) { mutableStateOf("") }
    var store by remember(product.slug) { mutableStateOf<Store?>(null) }
    var promo by remember(product.slug) { mutableStateOf("none") }
    var note by remember(product.slug) { mutableStateOf("") }
    var storePicker by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val priceFocus = remember { FocusRequester() }

    LaunchedEffect(product.slug) {
        delay(200)
        runCatching { priceFocus.requestFocus() }
    }

    val cents = Price.parseToCents(priceText)
    val ready = cents != null && store != null && !busy

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(product.label(), fontWeight = FontWeight.SemiBold)
                Text(
                    "What did you pay?",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                MoneyField(
                    priceText,
                    { priceText = it },
                    "Price",
                    modifier = Modifier.focusRequester(priceFocus),
                )
                if (store != null) {
                    OutlinedButton(onClick = { storePicker = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(store!!.name)
                    }
                } else {
                    OutlinedButton(onClick = { storePicker = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("Which store are you in?")
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("none" to "Normal", "special" to "Special", "clearance" to "Clearance")
                        .forEach { (key, label) ->
                            FilterChip(
                                selected = promo == key,
                                onClick = { promo = key },
                                label = { Text(label) },
                            )
                        }
                }
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it.take(300) },
                    label = { Text("Note (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Barcode " + barcode,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = ready,
                onClick = {
                    val chosenStore = store ?: return@TextButton
                    val paid = cents ?: return@TextButton
                    scope.launch {
                        busy = true
                        error = null
                        runCatching {
                            api.ensureDevice()
                            api.submitObservation(
                                storeId = chosenStore.id,
                                productSlug = product.slug,
                                priceCents = paid,
                                promoKind = promo,
                                note = note.ifBlank { null },
                            )
                        }.onSuccess { response ->
                            val flagged = response.optString("status") == "needs_review"
                            onSaved(
                                Price.aud(paid) + " at " + chosenStore.name +
                                    if (flagged) " — saved, flagged for review" else " — thanks!",
                            )
                        }.onFailure { error = it.message }
                        busy = false
                    }
                },
            ) { Text(if (busy) "Saving…" else "Save price") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )

    if (storePicker) {
        StorePickerDialog(
            prefs = prefs,
            onDismiss = { storePicker = false },
            onPick = {
                store = it
                storePicker = false
            },
        )
    }
}
