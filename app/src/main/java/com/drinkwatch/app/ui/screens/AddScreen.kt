package com.drinkwatch.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import com.drinkwatch.app.data.Variants
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
    // The chosen drink's flavour family: itself plus every other size/carton the
    // server knows. Chips are drawn from the family minus whichever row is held.
    var family by remember { mutableStateOf<List<Product>>(emptyList()) }
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
        family = emptyList()
        error = null
    }

    /**
     * Select a catalogue row, and offer its other sizes.
     *
     * Every entry point (manual pick, barcode, tag OCR, browse dialog) goes
     * through here, so whichever way the drink was found the user can correct
     * the size before the price is recorded.
     */
    fun choose(picked: Product) {
        product = picked
        family = emptyList()
        scope.launch {
            val siblings = runCatching { api.productVariants(picked.slug) }.getOrDefault(emptyList())
            family = Variants.sorted(listOf(picked) + siblings)
        }
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
                        choose(found)
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
                            prefillFromTag(api, text) { matched ->
                                if (product == null) choose(matched)
                            }
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
        searchResults = runCatching { api.searchProducts(query, 8, strict = true) }.getOrDefault(emptyList())
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
                        onDismissResults = { searchResults = emptyList() },
                        onPick = { picked ->
                            choose(picked)
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

                val held = product
                val otherSizes = family.filter { it.slug != held?.slug }
                if (held != null && otherSizes.isNotEmpty()) {
                    VariantChips(otherSizes) { picked -> product = picked }
                }
            }
        }
    }

    if (productPicker) {
        ProductPickerDialog(
            initialQuery = searchQuery.ifBlank { product?.name.orEmpty() },
            onDismiss = { productPicker = false },
            onPick = {
                choose(it)
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
    unknownBarcode?.let { code ->
        UnknownBarcodeDialog(
            barcode = code,
            onDismiss = { unknownBarcode = null },
            onPickFromCatalogue = {
                unknownBarcode = null
                productPicker = true
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
    onDismissResults: () -> Unit,
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
        Box {
            OutlinedTextField(
                value = query,
                onValueChange = onQuery,
                label = { Text("Search the catalogue") },
                placeholder = { Text("Brand and flavour, e.g. V Blue") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            DropdownMenu(
                expanded = results.isNotEmpty(),
                onDismissRequest = onDismissResults,
            ) {
                results.forEach { candidate ->
                    DropdownMenuItem(
                        text = { Text(candidate.label()) },
                        onClick = { onPick(candidate) },
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            when {
                query.trim().length < 2 ->
                    "Type at least two letters. Only drinks already in the catalogue can be added."
                results.isEmpty() ->
                    "No catalogue drink has that spelling. Check it, or browse the list."
                else ->
                    "${results.size} matches — pick the exact can."
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onChangeDrink) { Text("Browse all drinks") }
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
private fun UnknownBarcodeDialog(
    barcode: String,
    onDismiss: () -> Unit,
    onPickFromCatalogue: () -> Unit,
) {
    // The add flow only writes against rows that already exist. A barcode the
    // catalogue has never seen cannot be turned into a product from here: that
    // path let a misread or a typo invent a drink, which every price surface
    // then had to live with. Saying so is more useful than a form.
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Not in the catalogue") },
        text = {
            Text(
                "Barcode $barcode does not belong to any drink we list. Only drinks " +
                    "already in the catalogue can be added, so check the code or pick " +
                    "the drink itself.",
                style = MaterialTheme.typography.bodySmall,
            )
        },
        confirmButton = {
            TextButton(onClick = onPickFromCatalogue) { Text("Pick the drink") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/** The same drink in another size or carton: one tap instead of a new search. */
@Composable
private fun VariantChips(siblings: List<Product>, onPick: (Product) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "Other sizes of this flavour",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            siblings.forEach { sibling ->
                FilterChip(
                    selected = false,
                    onClick = { onPick(sibling) },
                    label = { Text(Variants.chipLabel(sibling)) },
                )
            }
        }
    }
}
