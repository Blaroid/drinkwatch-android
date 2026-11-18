package com.drinkwatch.app.ui.screens

import android.Manifest
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.drinkwatch.app.data.Geo
import com.drinkwatch.app.data.PlaceName
import com.drinkwatch.app.data.Api
import com.drinkwatch.app.data.Offer
import com.drinkwatch.app.data.Prefs
import com.drinkwatch.app.data.Price
import com.drinkwatch.app.data.ProductPrices
import com.drinkwatch.app.data.ServiceLocator
import com.drinkwatch.app.data.WidgetItem
import com.drinkwatch.app.ui.components.Chip
import com.drinkwatch.app.ui.components.ChipTone
import com.drinkwatch.app.ui.components.DealChip
import com.drinkwatch.app.ui.components.ErrorBar
import com.drinkwatch.app.ui.components.LoadingRow
import com.drinkwatch.app.ui.components.MoneyField
import com.drinkwatch.app.widget.WidgetRefresh
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { ServiceLocator.prefs(context) }
    val api = remember { ServiceLocator.api(context) }
    val scope = rememberCoroutineScope()

    var items by remember { mutableStateOf<List<WidgetItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var detailSlug by remember { mutableStateOf<String?>(null) }
    var locationTitle by remember { mutableStateOf(prefs.locationTitle()) }
    var locating by remember { mutableStateOf(false) }

    fun load() {
        scope.launch {
            loading = true
            error = null
            runCatching {
                api.ensureDevice()
                api.widgetItems(
                    prefs.lat,
                    prefs.lon,
                    prefs.radiusKm,
                    prefs.trackedSlugs,
                    suggest = if (prefs.trackedSlugs.isEmpty()) 0 else 4,
                )
            }.onSuccess {
                items = it
                WidgetRefresh.refreshNow(context)
            }.onFailure {
                error = it.message
                items = Api.itemsFromCache(prefs.widgetCache)
            }
            loading = false
        }
    }

    var wantLocation by remember { mutableStateOf(false) }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) wantLocation = true else error = "Location permission denied"
    }

    fun locateMe() {
        if (!Geo.hasPermission(context)) {
            permission.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
            return
        }
        wantLocation = true
    }

    LaunchedEffect(wantLocation) {
        if (!wantLocation) return@LaunchedEffect
        wantLocation = false
        locating = true
        error = null
        val position = Geo.current(context)
        if (position == null) {
            error = "No location fix yet — check that location is on"
        } else {
            val place = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                PlaceName.resolve(context, position.first, position.second)
            }
            prefs.applyPosition(position.first, position.second, place)
            locationTitle = prefs.locationTitle()
            WidgetRefresh.refreshNow(context)
            load()
        }
        locating = false
    }

    // Reload whenever the screen comes back: after tracking a drink, returning from
    // Settings, or coming back from the background.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { load() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Cheapest cans", fontWeight = FontWeight.SemiBold)
                        Text(
                            (if (locating) "locating…" else "near " + locationTitle) +
                                " · " + prefs.radiusKm + " km",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { locateMe() }) {
                        Icon(Icons.Filled.MyLocation, "Use my location")
                    }
                    IconButton(onClick = { load() }) { Icon(Icons.Filled.Refresh, "Refresh") }
                    IconButton(onClick = onOpenSettings) { Icon(Icons.Filled.Settings, "Settings") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            error?.let { Box(Modifier.padding(horizontal = 16.dp)) { ErrorBar(it, onRetry = { load() }) } }
            if (loading && items.isEmpty()) LoadingRow("Finding prices…")
            if (prefs.trackedSlugs.isEmpty() && items.isNotEmpty()) {
                Text(
                    "Showing the most widely stocked drinks. Pick your own in Settings.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val firstSuggestion = items.indexOfFirst { it.suggested }
                itemsIndexed(items, key = { _, item -> item.slug }) { index, item ->
                    if (index == firstSuggestion && firstSuggestion > 0) {
                        Column(Modifier.padding(top = 14.dp, bottom = 2.dp)) {
                            Text(
                                "That's the end of your list",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                "Other cans with fresh prices nearby",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    PriceCard(item) { detailSlug = item.slug }
                }
                if (!loading && items.isEmpty() && error == null) {
                    item {
                        Text(
                            "No prices yet. Add one from the Add tab, then refresh.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 24.dp),
                        )
                    }
                }
                item { Spacer(Modifier.height(80.dp)) }
            }
        }
    }

    detailSlug?.let { slug ->
        DrinkDetailSheet(
            slug = slug,
            prefs = prefs,
            api = api,
            onDismiss = { detailSlug = null },
            onTracked = { load() },
            onNavigate = { offer ->
                val intent = Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("geo:0,0?q=" + Uri.encode(offer.storeName + " " + (offer.suburb ?: ""))),
                )
                runCatching { context.startActivity(intent) }
                    .onFailure { error = "No maps app for directions" }
            },
        )
    }
}

@Composable
private fun PriceCard(item: WidgetItem, onClick: () -> Unit) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    item.label,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    item.store + (item.suburb?.let { " · " + it } ?: ""),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Chip(
                        if (item.source == "crowd") "crowd" else "online",
                        if (item.source == "crowd") ChipTone.Good else ChipTone.Neutral,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        Price.age(item.ageHours) + (item.distanceKm?.let { " · " + Price.km(it) } ?: ""),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    Price.aud(item.priceCents),
                    fontSize = 24.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
                item.perCanCents?.let {
                    Text(
                        Price.aud(it) + "/can",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DrinkDetailSheet(
    slug: String,
    prefs: Prefs,
    api: Api,
    onDismiss: () -> Unit,
    onNavigate: (Offer) -> Unit,
    onTracked: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var data by remember { mutableStateOf<ProductPrices?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var alertOpen by remember { mutableStateOf(false) }
    var alertMessage by remember { mutableStateOf<String?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    LaunchedEffect(slug) {
        loading = true
        runCatching { api.prices(slug, prefs.lat, prefs.lon, prefs.radiusKm) }
            .onSuccess { data = it }
            .onFailure { error = it.message }
        loading = false
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Text(
                data?.product?.label() ?: slug,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "Prices within " + prefs.radiusKm + " km",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            if (loading) LoadingRow()
            ErrorBar(error)
            alertMessage?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) }

            val offers = data?.offers.orEmpty()
            if (offers.isNotEmpty()) {
                LazyColumn(Modifier.height(340.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(offers) { offer ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onNavigate(offer) },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(offer.storeName, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    listOfNotNull(
                                        offer.chainName,
                                        offer.suburb,
                                        offer.distanceKm?.let { Price.km(it) },
                                    ).joinToString(" · "),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Chip(Price.age(offer.ageHours), ChipTone.Neutral)
                                    DealChip(offer.stats?.deal)
                                    if (offer.priceScope == "chain") {
                                        Spacer(Modifier.width(6.dp))
                                        Chip("online", ChipTone.Neutral)
                                    }
                                    Price.promoLabel(offer.promoKind, offer.promoQty, offer.promoTotalCents)?.let {
                                        Spacer(Modifier.width(6.dp))
                                        Chip(it, ChipTone.Warn)
                                    }
                                }
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(Price.aud(offer.priceCents), fontWeight = FontWeight.SemiBold)
                                offer.perCanCents?.let {
                                    Text(
                                        Price.aud(it) + "/can",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { alertOpen = true }) {
                        Icon(Icons.Filled.NotificationsActive, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Alert me")
                    }
                    TextButton(onClick = {
                        prefs.addTracked(slug)
                        WidgetRefresh.refreshNow(context)
                        alertMessage = "Added to your list"
                        onTracked()
                    }) { Text("Track this") }
                }
            } else if (!loading) {
                Text(
                    "No fresh prices nearby. Be the first: add one from the Add tab.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (alertOpen) {
        var threshold by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { alertOpen = false },
            title = { Text("Alert me when it drops") },
            text = {
                Column {
                    Text("Notify me when this is under:", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    MoneyField(threshold, { threshold = it }, "Price")
                }
            },
            confirmButton = {
                TextButton(
                    enabled = Price.parseToCents(threshold) != null,
                    onClick = {
                        val cents = Price.parseToCents(threshold) ?: return@TextButton
                        scope.launch {
                            runCatching { api.createAlert(slug, cents) }
                                .onSuccess {
                                    alertMessage = "Alert set at " + Price.aud(cents)
                                    alertOpen = false
                                }
                                .onFailure { alertMessage = it.message }
                        }
                    },
                ) { Text("Set alert") }
            },
            dismissButton = { TextButton(onClick = { alertOpen = false }) { Text("Cancel") } },
        )
    }
}
