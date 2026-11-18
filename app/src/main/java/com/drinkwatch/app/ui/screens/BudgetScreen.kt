package com.drinkwatch.app.ui.screens

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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.drinkwatch.app.data.MaxCaffeine
import com.drinkwatch.app.data.Price
import com.drinkwatch.app.data.ServiceLocator
import com.drinkwatch.app.ui.components.Chip
import com.drinkwatch.app.ui.components.ChipTone
import com.drinkwatch.app.ui.components.ErrorBar
import com.drinkwatch.app.ui.components.LoadingRow
import com.drinkwatch.app.ui.components.MoneyField
import com.drinkwatch.app.ui.components.PrimaryButton
import com.drinkwatch.app.ui.components.SectionCard
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BudgetScreen() {
    val context = LocalContext.current
    val prefs = remember { ServiceLocator.prefs(context) }
    val api = remember { ServiceLocator.api(context) }
    val scope = rememberCoroutineScope()

    var amount by remember { mutableStateOf("20") }
    var multiStore by remember { mutableStateOf(false) }
    var plan by remember { mutableStateOf<MaxCaffeine?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun run() {
        val cents = Price.parseToCents(amount)
        if (cents == null || cents < 100) {
            error = "Enter an amount like 20"
            return
        }
        scope.launch {
            loading = true
            error = null
            runCatching { api.maxCaffeine(cents, prefs.lat, prefs.lon, prefs.radiusKm, multiStore) }
                .onSuccess { plan = it }
                .onFailure { error = it.message }
            loading = false
        }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Budget", fontWeight = FontWeight.SemiBold) }) }) { padding ->
        Column(
            Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionCard(title = "Max caffeine for your money") {
                Text(
                    "Most caffeine per dollar works out to most millilitres per dollar, because Australian energy drinks are capped at 32 mg/100 ml.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                MoneyField(amount, { amount = it }, "Amount to spend")
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !multiStore, onClick = { multiStore = false }, label = { Text("One store") })
                    FilterChip(selected = multiStore, onClick = { multiStore = true }, label = { Text("Multiple stores") })
                }
                Spacer(Modifier.height(12.dp))
                PrimaryButton(if (loading) "Optimising…" else "Find the best cans", enabled = !loading) { run() }
            }

            if (loading) LoadingRow()
            ErrorBar(error, onRetry = { run() })

            plan?.let { best ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            best.totalCaffeineMg.toString() + " mg caffeine",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            best.totalMl.toString() + " ml · " + Price.aud(best.spentCents) + " spent, " +
                                Price.aud(best.leftoverCents) + " left",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (best.exceedsDailyLimit) {
                            Spacer(Modifier.height(6.dp))
                            Chip("Over the " + best.dailyLimitMg + " mg daily guideline", ChipTone.Warn)
                        }
                    }
                }

                best.items.forEach { item ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(item.label, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                item.storeName + (if (item.chainCode.isNotBlank()) " · " + item.chainCode else ""),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                            Text(Price.aud(item.costCents), fontWeight = FontWeight.SemiBold)
                            Text(
                                item.units.toString() + " cans · " + item.caffeineMg + " mg",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Text(
                    best.note,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(70.dp))
        }
    }
}
