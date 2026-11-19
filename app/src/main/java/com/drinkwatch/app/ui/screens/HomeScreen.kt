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
import com.drinkwatch.app.data.Alerts
import com.drinkwatch.app.data.Deal
import com.drinkwatch.app.data.Geo
import com.drinkwatch.app.data.History
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
import com.drinkwatch.app.ui.components.Sparkline
import com.drinkwatch.app.ui.components.ErrorBar
import com.drinkwatch.app.ui.components.LoadingRow
import com.drinkwatch.app.ui.components.NoteBar
import com.drinkwatch.app.ui.components.NewAlertDialog
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
    var note by remember { mutableStateOf(prefs.trackedNote) }
    var detailSlug by remember { mutableStateOf<String?>(null) }
    var locationTitle by remember { mutableStateOf(prefs.locationTitle()) }
    var locating by remember { mutableStateOf(false) }
    // "My drinks" or the best-value board; both are one tap from the top.
    var board by remember { mutableStateOf("drinks") }
    var deals by remember { mutableStateOf<List<Deal>>(emptyList()) }
    var dealMetric by remember { mutableStateOf("per_litre") }

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
                note = prefs.trackedNote
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
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                androidx.compose.material3.FilterChip(
                    selected = board == "drinks",
                    onClick = { board = "drinks" },
                    label = { Text("My drinks") },
                )
                androidx.compose.material3.FilterChip(
                    selected = board == "deals",
                    onClick = {
                        board = "deals"
                        load()
                    },
                    label = { Text("Best value") },
                )
            }
            error?.let { Box(Modifier.padding(horizontal = 16.dp)) { ErrorBar(it, onRetry = { load() }) } }
            note?.let {
                Box(Modifier.padding(horizontal = 16.dp)) {
                    NoteBar(it, onDismiss = {
                        prefs.trackedNote = null
                        note = null
                    })
                }
            }
            if (loading && items.isEmpty()) LoadingRow("Finding prices…")
            if (prefs.trackedSlugs.isEmpty() && items.isNotEmpty()) {
                Text(
                    "Showing the most widely stocked drinks. Pick your own in Settings.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }
            if (board == "deals") {
                Row(
                    Modifier.padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf("per_litre" to "$/L", "per_can" to "$/can", "price" to "price").forEach { (key, label) ->
                        androidx.compose.material3.FilterChip(
                            selected = dealMetric == key,
                            onClick = {
                                dealMetric = key
                                scope.launch {
                                    deals = runCatching {
                                        api.deals(prefs.lat, prefs.lon, prefs.radiusKm, key)
                                    }.getOrDefault(emptyList())
                                }
                            },
                            label = { Text(label) },
                        )
                    }
                }
            }
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (board == "deals") {
                    items(deals, key = { it.slug }) { deal ->
                        Card(
                            shape = RoundedCornerShape(18.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                            ),
                            modifier = Modifier.fillMaxWidth().clickable { detailSlug = deal.slug },
                        ) {
                            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(deal.label, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        deal.storeName + (deal.distanceKm?.let { " · " + Price.km(it) } ?: ""),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(
                                        Price.age(deal.ageHours),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(
                                        deal.perLitreCents?.let { (it / 100.0).let { v -> String.format(java.util.Locale.US, "$%.2f/L", v) } } ?: "",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                    Text(Price.aud(deal.priceCents), style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                    if (deals.isEmpty() && !loading) {
                        item {
                            Text(
                                "No prices nearby yet — the board fills up as prices are seen.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    item { Spacer(Modifier.height(80.dp)) }
                } else {
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
    var sortKey by remember { mutableStateOf("price") }
    var history by remember { mutableStateOf<History?>(null) }
    var confirming by remember { mutableStateOf<Int?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    LaunchedEffect(slug, sortKey) {
        loading = true
        runCatching { api.prices(slug, prefs.lat, prefs.lon, prefs.radiusKm, sortKey) }
            .onSuccess { data = it }
            .onFailure { error = it.message }
        loading = false
    }

    LaunchedEffect(slug) {
        history = runCatching { api.history(slug, prefs.lat, prefs.lon, prefs.radiusKm) }.getOrNull()
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

            history?.let { past ->
                if (past.points.size >= 2) {
                    Spacer(Modifier.height(8.dp))
                    Sparkline(past.points, past.medianCents)
                    Text(
                        "Last " + past.days + " days · median " + Price.aud(past.medianCents) +
                            " · low " + Price.aud(past.minCents),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("price" to "price", "per_can" to "$/can", "per_litre" to "$/L").forEach { (key, label) ->
                    androidx.compose.material3.FilterChip(
                        selected = sortKey == key,
                        onClick = { sortKey = key },
                        label = { Text(label) },
                    )
                }
            }

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
                                if (offer.priceScope == "store" && offer.observationId != null) {
                                    TextButton(
                                        enabled = confirming != offer.observationId,
                                        onClick = {
                                            val id = offer.observationId
                                            confirming = id
                                            scope.launch {
                                                runCatching {
                                                    api.confirmObservation(id, prefs.lat, prefs.lon)
                                                }.onSuccess {
                                                    alertMessage = "Thanks — price confirmed"
                                                }.onFailure {
                                                    alertMessage = it.message
                                                }
                                                confirming = null
                                            }
                                        },
                                    ) {
                                        Text(
                                            if (confirming == offer.observationId) "…" else "Still this price?",
                                            style = MaterialTheme.typography.labelSmall,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                val cheapest = offers.firstOrNull()
                val nearest = offers.minByOrNull { it.distanceKm ?: 999.0 }
                if (cheapest != null && nearest != null && cheapest.storeId != nearest.storeId) {
                    val fuel = ((cheapest.distanceKm ?: 0.0) * 2 * 0.085 * 204).toInt()
                    val saving = nearest.priceCents - cheapest.priceCents - fuel
                    Text(
                        (if (saving > 0) "Worth the drive: " else "Not worth the drive: ") +
                            "cheapest is " + Price.km(cheapest.distanceKm) + " away (≈" + Price.aud(fuel) +
                            " fuel), " + (if (saving > 0) "saving " else "costing ") + Price.aud(kotlin.math.abs(saving)) +
                            " vs " + nearest.storeName,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (saving > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
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
        NewAlertDialog(
            productSlug = slug,
            productLabel = data?.product?.label() ?: slug,
            prefs = prefs,
            onDismiss = { alertOpen = false },
            onSubmit = { request ->
                scope.launch {
                    runCatching { api.createAlert(request) }
                        .onSuccess {
                            alertMessage = "Alert set: " + Alerts.summary(
                                request.maxPriceCents,
                                request.minDropPercent,
                                null,
                                request.radiusKm,
                            )
                            alertOpen = false
                        }
                        .onFailure { alertMessage = it.message }
                }
            },
        )
    }
}
