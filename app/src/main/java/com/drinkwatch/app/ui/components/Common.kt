package com.drinkwatch.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.drinkwatch.app.data.Api
import com.drinkwatch.app.data.Prefs
import com.drinkwatch.app.data.Price
import com.drinkwatch.app.data.Product
import com.drinkwatch.app.data.ServiceLocator
import com.drinkwatch.app.data.Store
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
    ) {
        Column(Modifier.padding(16.dp)) {
            if (title != null) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(10.dp))
            }
            content()
        }
    }
}

@Composable
fun Chip(text: String, tone: ChipTone = ChipTone.Neutral) {
    val background = when (tone) {
        ChipTone.Good -> MaterialTheme.colorScheme.primaryContainer
        ChipTone.Warn -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.18f)
        ChipTone.Neutral -> MaterialTheme.colorScheme.surfaceVariant
    }
    val content = when (tone) {
        ChipTone.Good -> MaterialTheme.colorScheme.onPrimaryContainer
        ChipTone.Warn -> MaterialTheme.colorScheme.tertiary
        ChipTone.Neutral -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        Modifier
            .background(background, RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = content)
    }
}

enum class ChipTone { Neutral, Good, Warn }

@Composable
fun DealChip(deal: String?) {
    val (text, tone) = when (deal) {
        "great" -> "best in 90 days" to ChipTone.Good
        "good" -> "below usual" to ChipTone.Good
        "high" -> "above usual" to ChipTone.Warn
        else -> return
    }
    Spacer(Modifier.width(6.dp))
    Chip(text, tone)
}

@Composable
fun LoadingRow(text: String = "Loading…") {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(16.dp)) {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun ErrorBar(message: String?, onRetry: (() -> Unit)? = null) {
    if (message == null) return
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
            )
            if (onRetry != null) TextButton(onClick = onRetry) { Text("Retry") }
        }
    }
}

@Composable
fun MoneyField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { onValueChange(it.filter { ch -> ch.isDigit() || ch == '.' || ch == '$' }) },
        label = { Text(label) },
        prefix = { Text("$") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier.fillMaxWidth(),
    )
}

/** Search-and-pick dialog for drinks. */
@Composable
fun ProductPickerDialog(
    initialQuery: String = "",
    title: String = "Choose drink",
    onDismiss: () -> Unit,
    onPick: (Product) -> Unit,
) {
    val context = LocalContext.current
    val api = remember { ServiceLocator.api(context) }
    var query by remember { mutableStateOf(initialQuery) }
    var results by remember { mutableStateOf<List<Product>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(query) {
        delay(300)
        loading = true
        error = null
        runCatching { api.searchProducts(query.ifBlank { "energy" }, 30) }
            .onSuccess { results = it }
            .onFailure { error = it.message }
        loading = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search drinks") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                if (loading) LoadingRow()
                ErrorBar(error)
                LazyColumn(Modifier.height(320.dp)) {
                    items(results) { product ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onPick(product) }
                                .padding(vertical = 10.dp)
                        ) {
                            Text(product.label(), style = MaterialTheme.typography.bodyMedium)
                            Text(
                                product.slug,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Search-and-pick dialog for stores, biased to the current position. */
@Composable
fun StorePickerDialog(
    prefs: Prefs,
    title: String = "Choose store",
    onDismiss: () -> Unit,
    onPick: (Store) -> Unit,
) {
    val context = LocalContext.current
    val api = remember { ServiceLocator.api(context) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Store>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun load(text: String) {
        scope.launch {
            loading = true
            error = null
            runCatching {
                if (text.isBlank()) api.storesNearby(prefs.lat, prefs.lon, prefs.radiusKm, 25)
                else api.searchStores(text, prefs.lat, prefs.lon)
            }.onSuccess { results = it }.onFailure { error = it.message }
            loading = false
        }
    }

    LaunchedEffect(Unit) { load("") }
    LaunchedEffect(query) {
        if (query.isBlank()) return@LaunchedEffect
        delay(350)
        load(query)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search name, suburb or postcode") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                if (loading) LoadingRow()
                ErrorBar(error)
                LazyColumn(Modifier.height(320.dp)) {
                    items(results) { store ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onPick(store) }
                                .padding(vertical = 10.dp)
                        ) {
                            Text(store.name, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                listOfNotNull(
                                    store.chainName,
                                    store.suburb,
                                    store.distanceKm?.let { Price.km(it) },
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun PrimaryButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
    ) { Text(text) }
}

@Composable
fun KeyValueRow(key: String, value: String, emphasis: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(key, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (emphasis) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}
