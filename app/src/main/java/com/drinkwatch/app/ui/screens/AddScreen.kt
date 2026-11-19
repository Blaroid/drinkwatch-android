package com.drinkwatch.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.core.content.FileProvider
import com.drinkwatch.app.data.Api
import com.drinkwatch.app.data.Images
import com.drinkwatch.app.data.Ocr
import com.drinkwatch.app.data.PhotoClass
import com.drinkwatch.app.data.Prefs
import com.drinkwatch.app.data.Price
import com.drinkwatch.app.data.Product
import com.drinkwatch.app.data.ReceiptCandidate
import com.drinkwatch.app.data.ServiceLocator
import com.drinkwatch.app.data.Store
import com.drinkwatch.app.ui.components.ErrorBar
import com.drinkwatch.app.ui.components.MoneyField
import com.drinkwatch.app.ui.components.PrimaryButton
import com.drinkwatch.app.ui.components.ProductPickerDialog
import com.drinkwatch.app.ui.components.QuickPriceDialog
import com.drinkwatch.app.ui.components.SectionCard
import com.drinkwatch.app.ui.components.StorePickerDialog
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Three ways to add a price, each chosen explicitly before any form appears.
 *
 * The screen deliberately opens on the choice rather than a default mode: the
 * form below it used to be a long scroll away from whichever chip was selected,
 * so the field that needed attention could end up off-screen.
 */
private enum class Method(val title: String, val blurb: String) {
    Barcode(
        "Scan a barcode",
        "Point the camera at the barcode on the can. The digits can be entered by hand if the scanner will not focus.",
    ),
    Photo(
        "Price tag or receipt",
        "A price tag adds one drink. A receipt adds every drink on the docket at once. The type is detected from the photo.",
    ),
    Manual(
        "Type it in",
        "Search the catalogue for the drink, then record the price and the store.",
    ),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddScreen() {
    val context = LocalContext.current
    val prefs = remember { ServiceLocator.prefs(context) }
    val api = remember { ServiceLocator.api(context) }
    val scope = rememberCoroutineScope()

    var method by remember { mutableStateOf<Method?>(null) }
    var product by remember { mutableStateOf<Product?>(null) }
    var store by remember { mutableStateOf<Store?>(null) }
    var priceText by remember { mutableStateOf("") }
    var promo by remember { mutableStateOf("none") }
    var note by remember { mutableStateOf("") }
    var barcodeText by remember { mutableStateOf("") }
    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<Product>>(emptyList()) }
    var photoKind by remember { mutableStateOf<PhotoClass.Kind?>(null) }
    var receiptText by remember { mutableStateOf<String?>(null) }
    var receiptCandidates by remember { mutableStateOf<List<ReceiptCandidate>>(emptyList()) }
    var receiptSelected by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var photoBytes by remember { mutableStateOf<ByteArray?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var productPicker by remember { mutableStateOf(false) }
    var storePicker by remember { mutableStateOf(false) }
    var unknownBarcode by remember { mutableStateOf<String?>(null) }
    var quickProduct by remember { mutableStateOf<Product?>(null) }
    var quickBarcode by remember { mutableStateOf<String?>(null) }
    var pendingFile by remember { mutableStateOf<java.io.File?>(null) }
    var knownCode by remember { mutableStateOf<String?>(null) }

    fun tell(message: String) {
        android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
    }

    /** Back to the method chooser, with the per-attempt state cleared. */
    fun reset() {
        method = null
        product = null
        priceText = ""
        promo = "none"
        note = ""
        barcodeText = ""
        searchQuery = ""
        searchResults = emptyList()
        photoBytes = null
        photoKind = null
        receiptText = null
        receiptCandidates = emptyList()
        receiptSelected = emptySet()
        knownCode = null
        error = null
    }

    fun fail(message: String?) {
        error = message ?: "Something went wrong"
        tell(error!!)
    }

    fun lookup(code: String) {
        if (code.isBlank()) return
        scope.launch {
            busy = true
            error = null
            runCatching { api.barcode(code) }
                .onSuccess { hit ->
                    knownCode = code
                    val found = hit.product
                    if (found != null) {
                        product = found
                        // The drink is known, so the only question left is the store.
                        if (store == null) storePicker = true
                    } else {
                        unknownBarcode = code
                    }
                }
                .onFailure { failure ->
                    // No signal in the servo car park: the cached catalogue still
                    // knows the can, so say so instead of failing blankly.
                    val cached = com.drinkwatch.app.data.OfflineCatalogue(context).offlineLabel(code)
                    if (cached != null) {
                        fail("Offline: that is $cached. Reconnect to record the price.")
                    } else {
                        fail(failure.message)
                    }
                }
            busy = false
        }
    }

    fun startScan() {
        val scanner = GmsBarcodeScanning.getClient(context)
        scanner.startScan()
            .addOnSuccessListener { barcode -> lookup(barcode.rawValue.orEmpty()) }
            .addOnFailureListener { failure -> fail(failure.message) }
    }

    fun reportPhotoProblem(message: String) {
        error = message
        tell(message)
    }

    /** One entry point for camera and gallery: the text decides tag or receipt. */
    fun processPhoto(file: java.io.File) {
        scope.launch(Dispatchers.IO) {
            val bitmap = Images.load(file)
            if (bitmap == null) {
                withContext(Dispatchers.Main) { reportPhotoProblem("Could not read that photo") }
                return@launch
            }
            val jpeg = Images.toJpeg(bitmap)
            withContext(Dispatchers.Main) { photoBytes = jpeg }
            Ocr.readTag(bitmap) { cents, text ->
                scope.launch(Dispatchers.Main) {
                    receiptText = text
                    val kind = PhotoClass.classify(text)
                    photoKind = kind
                    when (kind) {
                        PhotoClass.Kind.Tag -> {
                            if (cents != null) {
                                priceText = String.format(java.util.Locale.US, "%.2f", cents / 100.0)
                            }
                            prefillFromTag(api, text) { matched -> product = product ?: matched }
                            if (cents == null && text.isBlank()) {
                                reportPhotoProblem("Could not read that tag. Type the price instead.")
                            }
                        }
                        PhotoClass.Kind.Receipt -> {
                            if (store == null) storePicker = true
                        }
                        PhotoClass.Kind.Unknown -> Unit
                    }
                }
            }
        }
    }

    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val file = pendingFile
        if (!ok || file == null || !file.exists()) return@rememberLauncherForActivityResult
        processPhoto(file)
    }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val file = Images.tempFile(context, "picked")
        // Copy and decode in the same coroutine: launching the copy and decoding
        // straight away raced it, and the decoder saw an empty file, so picking an
        // image from the gallery appeared to do nothing at all.
        scope.launch(Dispatchers.IO) {
            val copied = runCatching {
                context.contentResolver.openInputStream(uri)?.use { source ->
                    file.outputStream().use { target -> source.copyTo(target) }
                } != null
            }.getOrDefault(false)
            if (!copied) {
                withContext(Dispatchers.Main) { reportPhotoProblem("Could not read that image") }
                return@launch
            }
            processPhoto(file)
        }
    }

    fun shoot() {
        val file = Images.tempFile(context, "tag")
        pendingFile = file
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        takePicture.launch(uri)
    }

    fun submit() {
        val cents = Price.parseToCents(priceText)
        val chosenStore = store
        val chosenProduct = product
        when {
            chosenProduct == null -> return fail("Choose the drink first")
            cents == null -> return fail("Enter the price you saw")
            chosenStore == null -> return fail("Choose the store you are in")
        }
        scope.launch {
            busy = true
            error = null
            runCatching {
                api.ensureDevice()
                val photoToken = photoBytes?.let { api.uploadPhoto(it) }
                api.submitObservation(
                    storeId = chosenStore!!.id,
                    productSlug = chosenProduct!!.slug,
                    priceCents = cents!!,
                    promoKind = promo,
                    note = note.ifBlank { null },
                    photoToken = photoToken,
                )
            }.onSuccess { response ->
                tell(
                    if (response.optString("status") == "needs_review") {
                        "Saved and flagged for review (far from the usual price)"
                    } else {
                        "Recorded ${chosenProduct!!.label()} at ${chosenStore!!.name}"
                    }
                )
                reset()
            }.onFailure { fail(it.message) }
            busy = false
        }
    }

    fun submitReceipt() {
        val chosenStore = store ?: return fail("Choose the store this receipt is from")
        val items = receiptCandidates.mapIndexedNotNull { index, candidate ->
            if (index !in receiptSelected) return@mapIndexedNotNull null
            val slug = candidate.productSlug ?: return@mapIndexedNotNull null
            val cents = candidate.priceCents ?: return@mapIndexedNotNull null
            Triple(chosenStore.id, slug, cents)
        }
        if (items.isEmpty()) return fail("Tick at least one line")
        scope.launch {
            busy = true
            error = null
            runCatching { api.ensureDevice(); api.submitBatch(items) }
                .onSuccess {
                    tell("Added ${items.size} prices from the receipt")
                    reset()
                }
                .onFailure { fail(it.message) }
            busy = false
        }
    }

    // A receipt needs a store before the lines mean anything, so parse as soon as
    // one is chosen instead of making the user hunt for another button.
    LaunchedEffect(method, photoKind, receiptText, store) {
        val text = receiptText
        if (method == Method.Photo &&
            photoKind == PhotoClass.Kind.Receipt &&
            text != null &&
            store != null &&
            receiptCandidates.isEmpty() &&
            !busy
        ) {
            busy = true
            runCatching { api.ensureDevice(); api.parseReceipt(text) }
                .onSuccess { candidates ->
                    receiptCandidates = candidates.filter { it.priceCents != null }
                    receiptSelected = receiptCandidates
                        .mapIndexedNotNull { index, candidate -> if (candidate.productSlug != null) index else null }
                        .toSet()
                    if (receiptCandidates.isEmpty()) {
                        fail("No drinks matched on that receipt. Use the price tag option per can.")
                    }
                }
                .onFailure { fail(it.message) }
            busy = false
        }
    }

    // Inline catalogue search for the manual option: results appear on the screen
    // instead of behind a dialog, so the first match is visible without a tap.
    LaunchedEffect(searchQuery) {
        val query = searchQuery.trim()
        if (query.length < 2) {
            searchResults = emptyList()
            return@LaunchedEffect
        }
        delay(300)
        searchResults = runCatching { api.searchProducts(query, 8) }.getOrDefault(emptyList())
    }

    BackHandler(enabled = method != null) { reset() }

    val canSave = product != null && Price.parseToCents(priceText) != null && store != null && !busy
    val action: BottomAction? = when (method) {
        null -> null
        Method.Barcode -> if (product == null) {
            BottomAction(if (busy) "Looking up…" else "Scan barcode", !busy) { startScan() }
        } else {
            BottomAction(if (busy) "Saving…" else "Save this price", canSave) { submit() }
        }
        Method.Manual -> BottomAction(if (busy) "Saving…" else "Save this price", canSave) { submit() }
        Method.Photo -> when {
            photoKind == PhotoClass.Kind.Receipt -> BottomAction(
                if (busy) "Adding…" else "Add ${receiptSelected.size} prices",
                receiptSelected.isNotEmpty() && store != null && !busy,
            ) { submitReceipt() }
            photoKind == null -> null
            else -> BottomAction(if (busy) "Saving…" else "Save this price", canSave) { submit() }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (method) {
                            null -> "Add a price"
                            Method.Barcode -> "Scan a barcode"
                            Method.Photo -> "Price tag or receipt"
                            Method.Manual -> "Type it in"
                        },
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                navigationIcon = {
                    if (method != null) {
                        TextButton(onClick = { reset() }) { Text("Back") }
                    }
                },
            )
        },
        bottomBar = {
            action?.let { chosen ->
                Surface(tonalElevation = 3.dp) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        PrimaryButton(chosen.label, chosen.enabled, chosen.onClick)
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            // Errors sit directly under the header, above everything else, so they
            // are read where the eye already is rather than at the end of a scroll.
            if (error != null) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    ErrorBar(error) { error = null; if (method == Method.Barcode) startScan() }
                }
            }
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (method) {
                    null -> ChooserCard { picked -> method = picked; error = null }

                    Method.Barcode -> BarcodePane(
                        knownCode = knownCode,
                        product = product,
                        busy = busy,
                        barcodeText = barcodeText,
                        onBarcodeText = { barcodeText = it.filter { ch -> ch.isDigit() }.take(14) },
                        onLookup = { lookup(barcodeText) },
                        onChangeDrink = { productPicker = true },
                    ) {
                        PriceForm(
                            product = product,
                            store = store,
                            priceText = priceText,
                            promo = promo,
                            note = note,
                            onPrice = { priceText = it },
                            onPromo = { promo = it },
                            onNote = { note = it },
                            onPickDrink = { productPicker = true },
                            onPickStore = { storePicker = true },
                        )
                    }

                    Method.Photo -> PhotoPane(
                        kind = photoKind,
                        receiptText = receiptText,
                        priceText = priceText,
                        product = product,
                        busy = busy,
                        candidates = receiptCandidates,
                        selected = receiptSelected,
                        storeName = store?.let { it.name },
                        onShoot = { shoot() },
                        onGallery = {
                            pickImage.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        onOverride = { kind ->
                            photoKind = kind
                            receiptCandidates = emptyList()
                            receiptSelected = emptySet()
                        },
                        onToggle = { index, on ->
                            receiptSelected = if (on) receiptSelected + index else receiptSelected - index
                        },
                        onPickStore = { storePicker = true },
                    ) {
                        PriceForm(
                            product = product,
                            store = store,
                            priceText = priceText,
                            promo = promo,
                            note = note,
                            onPrice = { priceText = it },
                            onPromo = { promo = it },
                            onNote = { note = it },
                            onPickDrink = { productPicker = true },
                            onPickStore = { storePicker = true },
                        )
                    }

                    Method.Manual -> ManualPane(
                        query = searchQuery,
                        results = searchResults,
                        product = product,
                        onQuery = {
                            searchQuery = it
                            if (it.isBlank()) product = null
                        },
                        onPick = { picked ->
                            product = picked
                            searchResults = emptyList()
                            searchQuery = ""
                        },
                        onChangeDrink = { productPicker = true },
                    ) {
                        PriceForm(
                            product = product,
                            store = store,
                            priceText = priceText,
                            promo = promo,
                            note = note,
                            onPrice = { priceText = it },
                            onPromo = { promo = it },
                            onNote = { note = it },
                            onPickDrink = { productPicker = true },
                            onPickStore = { storePicker = true },
                        )
                    }
                }
            }
        }
    }

    if (productPicker) {
        ProductPickerDialog(
            initialQuery = searchQuery.ifBlank { product?.name.orEmpty() },
            onDismiss = { productPicker = false },
            onPick = {
                product = it
                productPicker = false
                if (store == null) storePicker = true
            },
        )
    }
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
    quickProduct?.let { chosen ->
        QuickPriceDialog(
            product = chosen,
            barcode = quickBarcode.orEmpty(),
            api = api,
            prefs = prefs,
            onDismiss = { quickProduct = null },
            onSaved = { message ->
                quickProduct = null
                tell(message)
            },
        )
    }
    unknownBarcode?.let { code ->
        NewProductDialog(
            barcode = code,
            suggestedName = null,
            suggestedBrand = null,
            api = api,
            prefs = prefs,
            onDismiss = { unknownBarcode = null },
            onDone = { added, _ ->
                unknownBarcode = null
                product = added
                quickProduct = added
                quickBarcode = code
            },
        )
    }
}

private data class BottomAction(val label: String, val enabled: Boolean, val onClick: () -> Unit)

/** The landing state: three equal, unmistakable choices. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChooserCard(onPick: (Method) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "How would you like to add it?",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Method.entries.forEach { option ->
            Card(
                onClick = { onPick(option) },
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(option.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            option.blurb,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

@Composable
private fun BarcodePane(
    knownCode: String?,
    product: Product?,
    busy: Boolean,
    barcodeText: String,
    onBarcodeText: (String) -> Unit,
    onLookup: () -> Unit,
    onChangeDrink: () -> Unit,
    form: @Composable () -> Unit,
) {
    if (product == null) {
        SectionCard(title = "Scan the can") {
            Text(
                "The camera reads the barcode and finds the drink. Nothing is saved until the price is confirmed.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SectionCard(title = "The scanner will not focus?") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = barcodeText,
                    onValueChange = onBarcodeText,
                    label = { Text("Barcode digits") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onLookup, enabled = barcodeText.length >= 8 && !busy) { Text("Look up") }
            }
        }
    } else {
        knownCode?.let {
            Text(
                "Barcode $it",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                product.label(),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onChangeDrink) { Text("Change") }
        }
        form()
    }
}

@Composable
private fun PhotoPane(
    kind: PhotoClass.Kind?,
    receiptText: String?,
    priceText: String,
    product: Product?,
    busy: Boolean,
    candidates: List<ReceiptCandidate>,
    selected: Set<Int>,
    storeName: String?,
    onShoot: () -> Unit,
    onGallery: () -> Unit,
    onOverride: (PhotoClass.Kind) -> Unit,
    onToggle: (Int, Boolean) -> Unit,
    onPickStore: () -> Unit,
    form: @Composable () -> Unit,
) {
    if (kind == null) {
        SectionCard(title = "Photograph the tag or the docket") {
            Text(
                "A price tag adds one drink. A receipt adds every drink on it. The photo decides which, and the result is shown before anything is saved.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            PrimaryButton(if (busy) "Reading…" else "Take photo", !busy) { onShoot() }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onGallery,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
            ) { Text("Choose from gallery") }
        }
        return
    }

    val lines = receiptText?.lines()?.count { it.isNotBlank() } ?: 0
    val (headline, overrideLabel, overrideTo) = when (kind) {
        PhotoClass.Kind.Receipt -> Triple("Receipt detected · $lines lines", "This is a price tag", PhotoClass.Kind.Tag)
        PhotoClass.Kind.Tag -> Triple("Price tag detected", "This is a receipt", PhotoClass.Kind.Receipt)
        PhotoClass.Kind.Unknown -> Triple("Could not tell what this is", "", PhotoClass.Kind.Tag)
    }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                headline,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            when (kind) {
                PhotoClass.Kind.Unknown -> Row {
                    TextButton(onClick = { onOverride(PhotoClass.Kind.Tag) }) { Text("Price tag") }
                    TextButton(onClick = { onOverride(PhotoClass.Kind.Receipt) }) { Text("Receipt") }
                }
                else -> TextButton(onClick = { onOverride(overrideTo) }) { Text(overrideLabel) }
            }
        }
    }

    if (kind == PhotoClass.Kind.Receipt) {
        SectionCard(title = "Which store is this receipt from?") {
            OutlinedButton(
                onClick = onPickStore,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
            ) { Text(storeName ?: "Choose store") }
            Spacer(Modifier.height(6.dp))
            Text(
                "All lines on the receipt are recorded at this store.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (busy) {
            Text("Matching the lines…", style = MaterialTheme.typography.bodySmall)
        }
        if (candidates.isNotEmpty()) {
            SectionCard(title = "${selected.size} of ${candidates.size} lines matched") {
                candidates.forEachIndexed { index, candidate ->
                    val matched = candidate.productSlug != null
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable(enabled = matched) { onToggle(index, index !in selected) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = index in selected,
                            enabled = matched,
                            onCheckedChange = { on -> onToggle(index, on) },
                        )
                        Column(Modifier.weight(1f)) {
                            Text(
                                candidate.label ?: "No catalogue match",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (matched) {
                                    MaterialTheme.colorScheme.onSurface
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                            Text(
                                candidate.line.take(44),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(Price.aud(candidate.priceCents), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        return
    }

    // Price tag (or an override to one): one drink, one price.
    if (kind == PhotoClass.Kind.Tag && product == null && priceText.isNotBlank()) {
        Text(
            "Price read from the photo: ${Price.aud(Price.parseToCents(priceText))}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    form()
}

@Composable
private fun ManualPane(
    query: String,
    results: List<Product>,
    product: Product?,
    onQuery: (String) -> Unit,
    onPick: (Product) -> Unit,
    onChangeDrink: () -> Unit,
    form: @Composable () -> Unit,
) {
    if (product != null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(product.label(), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = onChangeDrink) { Text("Change") }
        }
        form()
        return
    }
    SectionCard(title = "Which drink?") {
        OutlinedTextField(
            value = query,
            onValueChange = onQuery,
            label = { Text("Search the catalogue") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        if (results.isEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(
                if (query.length < 2) "Type at least two letters, or browse the full list." else "No match yet.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onChangeDrink) { Text("Browse all drinks") }
        } else {
            results.take(6).forEach { candidate ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onPick(candidate) }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(candidate.label(), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    Text("add", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

/** Price, store and promotion for a single drink, in the order they are known. */
@Composable
private fun PriceForm(
    product: Product?,
    store: Store?,
    priceText: String,
    promo: String,
    note: String,
    onPrice: (String) -> Unit,
    onPromo: (String) -> Unit,
    onNote: (String) -> Unit,
    onPickDrink: () -> Unit,
    onPickStore: () -> Unit,
) {
    SectionCard(title = "What did you find?") {
        if (product == null) {
            OutlinedButton(
                onClick = onPickDrink,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
            ) { Text("Choose drink") }
        }
        Spacer(Modifier.height(10.dp))
        MoneyField(priceText, onPrice, "Price on the tag")
        Spacer(Modifier.height(10.dp))
        if (store != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(store.name, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        listOfNotNull(store.chainName, store.suburb, store.distanceKm?.let { Price.km(it) })
                            .joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onPickStore) { Text("Change") }
            }
        } else {
            OutlinedButton(
                onClick = onPickStore,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
            ) { Text("Which store are you in?") }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("none" to "Normal price", "special" to "Special", "clearance" to "Clearance")
                .forEach { (key, label) ->
                    FilterChip(selected = promo == key, onClick = { onPromo(key) }, label = { Text(label) })
                }
        }
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = note,
            onValueChange = onNote,
            label = { Text("Note (optional)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** The tag usually names the can, so try to preselect it from the label text. */
private suspend fun prefillFromTag(api: Api, text: String, onMatch: (Product) -> Unit) {
    if (text.isBlank()) return
    val words = text.lowercase()
    val match = runCatching { api.searchProducts(text.take(60), 8) }.getOrDefault(emptyList())
        .firstOrNull { candidate ->
            words.contains(candidate.brand.lowercase()) &&
                (candidate.sizeMl == null || words.contains(candidate.sizeMl.toString()))
        }
    if (match != null) onMatch(match)
}

@Composable
private fun NewProductDialog(
    barcode: String,
    suggestedName: String?,
    suggestedBrand: String?,
    api: Api,
    prefs: Prefs,
    onDismiss: () -> Unit,
    onDone: (Product, String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    // Open Food Facts occasionally knows the can before we do; start from that.
    var name by remember { mutableStateOf(suggestedName.orEmpty()) }
    var brand by remember { mutableStateOf(suggestedBrand.orEmpty()) }
    var size by remember { mutableStateOf(sizeFromName(suggestedName.orEmpty())) }
    var sizeEdited by remember { mutableStateOf(false) }
    var price by remember { mutableStateOf("") }
    var store by remember { mutableStateOf<Store?>(null) }
    var storePicker by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var matches by remember { mutableStateOf<List<Product>>(emptyList()) }

    // Live completion against the catalogue: the fastest way to say "it is this
    // one" without retyping a name we already know.
    LaunchedEffect(name) {
        if (name.trim().length < 2) {
            matches = emptyList()
            return@LaunchedEffect
        }
        delay(300)
        matches = runCatching { api.searchProducts(name.trim(), 6) }.getOrDefault(emptyList())
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New can") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Barcode $barcode is not in the catalogue yet. Type what is printed on the can and it will be added for review.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { typed ->
                        name = typed.take(160)
                        if (!sizeEdited) size = sizeFromName(typed)
                    },
                    label = { Text("Name on the can") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                // Suggestions: tap one to adopt its brand and size.
                matches.take(4).forEach { candidate ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                name = candidate.label()
                                brand = candidate.brand
                                size = candidate.sizeMl?.toString() ?: size
                                sizeEdited = true
                                matches = emptyList()
                            }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            candidate.label(),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "use",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = brand,
                        onValueChange = { brand = it.take(60) },
                        label = { Text("Brand") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = size,
                        onValueChange = {
                            size = it.filter { ch -> ch.isDigit() }
                            sizeEdited = true
                        },
                        label = { Text("ml") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.width(90.dp),
                    )
                }
                MoneyField(price, { price = it }, "Price (optional)")
                OutlinedButton(onClick = { storePicker = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(store?.name ?: "Store (optional)")
                }
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.length >= 2 && !busy,
                onClick = {
                    scope.launch {
                        busy = true
                        error = null
                        runCatching {
                            api.ensureDevice()
                            api.submitPending(
                                barcode = barcode,
                                name = name,
                                brand = brand.ifBlank { null },
                                sizeMl = size.toIntOrNull(),
                                storeId = store?.id,
                                priceCents = Price.parseToCents(price),
                                photoToken = null,
                            )
                        }.onSuccess { response ->
                            val added = response.optJSONObject("product")
                            val product = added?.let { json ->
                                Product(
                                    id = json.optInt("id"),
                                    slug = json.optString("slug"),
                                    brand = json.optString("brand"),
                                    name = json.optString("name"),
                                    variant = if (json.isNull("variant")) null else json.optString("variant"),
                                    sizeMl = if (json.isNull("size_ml")) null else json.optInt("size_ml"),
                                    packCount = json.optInt("pack_count", 1),
                                    barcode = if (json.isNull("barcode")) null else json.optString("barcode"),
                                    category = json.optString("category", "energy"),
                                )
                            }
                            if (product != null) {
                                onDone(product, "Added " + product.label() + ". What did you pay?")
                            } else {
                                onDone(
                                    Product(0, "", brand, name, null, size.toIntOrNull(), 1, null, "energy"),
                                    "Added $name. What did you pay?",
                                )
                            }
                        }.onFailure { error = it.message }
                        busy = false
                    }
                },
            ) { Text(if (busy) "Adding…" else "Add and enter price") }
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

/** Pull "500ml" or "4 x 500ml" out of whatever the label says. */
private fun sizeFromName(text: String): String {
    val multi = Regex("""(\d{1,2})\s*[x×]\s*(\d{2,4})\s*ml""", RegexOption.IGNORE_CASE).find(text)
    if (multi != null) return multi.groupValues[2]
    val single = Regex("""(\d{2,4})\s*ml""", RegexOption.IGNORE_CASE).find(text)
    return single?.groupValues?.get(1) ?: ""
}
