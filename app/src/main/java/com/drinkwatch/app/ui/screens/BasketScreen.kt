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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
import com.drinkwatch.app.data.BasketRequestItem
import com.drinkwatch.app.data.BasketResult
import com.drinkwatch.app.data.BasketStorage
import com.drinkwatch.app.data.Price
import com.drinkwatch.app.data.RoutePlan
import com.drinkwatch.app.data.ServiceLocator
import com.drinkwatch.app.ui.components.Chip
import com.drinkwatch.app.ui.components.ChipTone
import com.drinkwatch.app.ui.components.ErrorBar
import com.drinkwatch.app.ui.components.LoadingRow
import com.drinkwatch.app.ui.components.PrimaryButton
import com.drinkwatch.app.ui.components.ProductPickerDialog
import com.drinkwatch.app.ui.components.SectionCard
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BasketScreen() {
    val context = LocalContext.current
    val prefs = remember { ServiceLocator.prefs(context) }
    val api = remember { ServiceLocator.api(context) }
    val basketStore = remember { BasketStorage(prefs) }
    val scope = rememberCoroutineScope()

    var items by remember { mutableStateOf(basketStore.load()) }
    var picker by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<BasketResult?>(null) }
    var route by remember { mutableStateOf<RoutePlan?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun persist(next: List<BasketRequestItem>) {
        items = next
        basketStore.save(next)
        result = null
        route = null
    }

    fun compare() {
        if (items.isEmpty()) return
        scope.launch {
            loading = true
            error = null
            runCatching { api.compareBasket(items, prefs.lat, prefs.lon, prefs.radiusKm) }
                .onSuccess { result = it; route = null }
                .onFailure { error = it.message }
            loading = false
        }
    }

    fun planRoute() {
        if (items.isEmpty()) return
        scope.launch {
            loading = true
            error = null
            runCatching { api.routePlan(items, prefs.lat, prefs.lon, prefs.radiusKm) }
                .onSuccess { route = it }
                .onFailure { error = it.message }
            loading = false
        }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Basket", fontWeight = FontWeight.SemiBold) }) }) { padding ->
        Column(
            Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionCard(title = "What are you buying?") {
                if (items.isEmpty()) {
                    Text(
                        "Add a drink and a quantity to compare pack sizes and stores.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                items.forEach { item ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f)) {
                            Text(item.label ?: item.slug, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "qty " + item.quantity,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = {
                            persist(items.map { if (it.slug == item.slug) it.copy(quantity = (it.quantity - 1).coerceAtLeast(1)) else it })
                        }) { Icon(Icons.Filled.Remove, "Less") }
                        IconButton(onClick = {
                            persist(items.map { if (it.slug == item.slug) it.copy(quantity = it.quantity + 1) else it })
                        }) { Icon(Icons.Filled.Add, "More") }
                        IconButton(onClick = { persist(items.filterNot { it.slug == item.slug }) }) {
                            Icon(Icons.Filled.Delete, "Remove")
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                OutlinedButton(onClick = { picker = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Add, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Add drink")
                }
                Spacer(Modifier.height(10.dp))
                PrimaryButton("Compare stores", enabled = items.isNotEmpty() && !loading) { compare() }
            }

            if (loading) LoadingRow("Comparing…")
            ErrorBar(error, onRetry = { compare() })

            result?.let { basket ->
                val best = basket.stores.firstOrNull { it.complete }
                if (best != null) {
                    SectionCard(title = "Cheapest single store") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(best.storeName, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                                Text(
                                    best.chainCode + " · " + Price.km(best.distanceKm),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                Price.aud(best.totalCents),
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        best.items.forEach { line ->
                            Spacer(Modifier.height(6.dp))
                            Text(
                                (line.label ?: line.requested) + " — " + line.breakdown.joinToString(", "),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        basket.savingsVsWorstCents?.let {
                            Spacer(Modifier.height(8.dp))
                            Chip("Save " + Price.aud(it) + " vs the dearest store", ChipTone.Good)
                        }
                    }
                }

                basket.multiStoreTotalCents?.let { total ->
                    SectionCard(title = "Split across stores") {
                        Text(
                            total.toString().let { Price.aud(total) } + " total",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        basket.multiStorePlan.forEach { line ->
                            Text(
                                (line.storeName) + " — " + line.costCents.let { Price.aud(it) },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = { planRoute() }, modifier = Modifier.fillMaxWidth()) {
                            Text("Work out the drive")
                        }
                    }
                }

                if (basket.stores.size > 1) {
                    SectionCard(title = "Other stores") {
                        basket.stores.drop(1).take(6).forEach { store ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                Text(
                                    store.storeName,
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                if (!store.complete) Chip("missing items", ChipTone.Warn)
                                Spacer(Modifier.width(8.dp))
                                Text(Price.aud(store.totalCents), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }

            route?.let { plan ->
                SectionCard(title = "The drive") {
                    Text(
                        plan.stops.joinToString(" → ") { it.storeName },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        Price.km(plan.travelKm) + " · fuel " + Price.aud(plan.fuelCostCents) +
                            (plan.fuelPriceCentsPerL?.let { " (at " + it + "c/L)" } ?: ""),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    plan.netSavingCents?.let { saving ->
                        Spacer(Modifier.height(6.dp))
                        if (saving > 0) Chip("Worth it: saves " + Price.aud(saving), ChipTone.Good)
                        else Chip("Not worth it: costs " + Price.aud(-saving) + " extra", ChipTone.Warn)
                    }
                }
            }

            if (result != null && result!!.stores.isEmpty()) {
                Text(
                    "No store nearby has all of these. Try a bigger radius in Settings.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(70.dp))
        }
    }

    if (picker) {
        ProductPickerDialog(
            title = "Add to basket",
            onDismiss = { picker = false },
            onPick = { product ->
                picker = false
                val existing = items.firstOrNull { item -> item.slug == product.slug }
                val next: List<BasketRequestItem> = if (existing == null) {
                    items + BasketRequestItem(slug = product.slug, quantity = 1, label = product.label())
                } else {
                    items.map { item ->
                        if (item.slug == product.slug) item.copy(quantity = item.quantity + 1) else item
                    }
                }
                persist(next)
            },
        )
    }
}
