package com.drinkwatch.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.drinkwatch.app.data.Api
import com.drinkwatch.app.data.Images
import com.drinkwatch.app.data.Ocr
import com.drinkwatch.app.data.Prefs
import com.drinkwatch.app.data.Price
import com.drinkwatch.app.data.Product
import com.drinkwatch.app.data.ServiceLocator
import com.drinkwatch.app.data.Store
import com.drinkwatch.app.ui.components.ErrorBar
import com.drinkwatch.app.ui.components.MoneyField
import com.drinkwatch.app.ui.components.PrimaryButton
import com.drinkwatch.app.ui.components.ProductPickerDialog
import com.drinkwatch.app.ui.components.SectionCard
import com.drinkwatch.app.ui.components.StorePickerDialog
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlinx.coroutines.launch

private enum class AddMode(val label: String) { Barcode("Barcode"), Tag("Price tag"), Manual("Type it in") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddScreen() {
    val context = LocalContext.current
    val prefs = remember { ServiceLocator.prefs(context) }
    val api = remember { ServiceLocator.api(context) }
    val scope = rememberCoroutineScope()

    var mode by remember { mutableStateOf(AddMode.Barcode) }
    var product by remember { mutableStateOf<Product?>(null) }
    var unknownBarcode by remember { mutableStateOf<String?>(null) }
    var barcodeText by remember { mutableStateOf("") }
    var priceText by remember { mutableStateOf("") }
    var store by remember { mutableStateOf<Store?>(null) }
    var promo by remember { mutableStateOf("none") }
    var note by remember { mutableStateOf("") }
    var photoBytes by remember { mutableStateOf<ByteArray?>(null) }
    var ocrText by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var productPicker by remember { mutableStateOf(false) }
    var storePicker by remember { mutableStateOf(false) }
    var pendingDialog by remember { mutableStateOf(false) }

    fun lookup(code: String) {
        if (code.isBlank()) return
        scope.launch {
            busy = true
            error = null
            status = null
            runCatching { api.barcode(code) }
                .onSuccess { hit ->
                    if (hit.product != null) {
                        product = hit.product
                        unknownBarcode = null
                        status = null
                    } else {
                        unknownBarcode = code
                        pendingDialog = true
                    }
                }
                .onFailure { error = it.message }
            busy = false
        }
    }

    val scanner = remember { GmsBarcodeScanning.getClient(context) }
    fun startScan() {
        error = null
        scanner.startScan()
            .addOnSuccessListener { barcode -> barcode.rawValue?.let { lookup(it) } }
            .addOnFailureListener { error = "Scanner unavailable: " + (it.message ?: "try typing the barcode") }
    }

    var pendingFile by remember { mutableStateOf<java.io.File?>(null) }
    fun processPhoto(file: java.io.File) {
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val bitmap = Images.load(file)
            if (bitmap == null) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { error = "Could not read that photo" }
                return@launch
            }
            val jpeg = Images.toJpeg(bitmap)
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { photoBytes = jpeg }
            Ocr.readTag(bitmap) { cents, text ->
                scope.launch(kotlinx.coroutines.Dispatchers.Main) {
                    ocrText = text.take(200)
                    if (cents != null) priceText = String.format(java.util.Locale.US, "%.2f", cents / 100.0)
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
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { source ->
                    file.outputStream().use { target -> source.copyTo(target) }
                }
            }.onFailure { error = "Could not read that image" }
        }
        processPhoto(file)
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
        if (cents == null) {
            error = "Enter the price you saw"
            return
        }
        if (chosenStore == null) {
            error = "Pick the store you are in"
            return
        }
        if (chosenProduct == null) {
            error = "Pick which drink it was"
            return
        }
        scope.launch {
            busy = true
            error = null
            runCatching {
                api.ensureDevice()
                val photoToken = photoBytes?.let { api.uploadPhoto(it) }
                api.submitObservation(
                    storeId = chosenStore.id,
                    productSlug = chosenProduct.slug,
                    priceCents = cents,
                    promoKind = promo,
                    note = note.ifBlank { null },
                    photoToken = photoToken,
                )
            }.onSuccess { response ->
                status = if (response.optString("status") == "needs_review") {
                    "Saved, but flagged for review (far from the usual price)."
                } else {
                    "Thanks! " + chosenProduct.label() + " at " + chosenStore.name + " recorded."
                }
                priceText = ""
                note = ""
                promo = "none"
                photoBytes = null
                ocrText = null
            }.onFailure { error = it.message }
            busy = false
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Add a price", fontWeight = FontWeight.SemiBold) }) },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AddMode.entries.forEach { candidate ->
                    FilterChip(
                        selected = mode == candidate,
                        onClick = { mode = candidate },
                        label = { Text(candidate.label) },
                    )
                }
            }

            when (mode) {
                AddMode.Barcode -> SectionCard(title = "Scan the can") {
                    Text(
                        "Point the camera at the barcode on the can.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    PrimaryButton(if (busy) "Looking up…" else "Scan barcode", enabled = !busy) { startScan() }
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = barcodeText,
                            onValueChange = { barcodeText = it.filter { ch -> ch.isDigit() } },
                            label = { Text("Or type the digits") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = { lookup(barcodeText) }, enabled = barcodeText.length >= 8) { Text("Look up") }
                    }
                }

                AddMode.Tag -> SectionCard(title = "Price tag") {
                    Text(
                        "Photograph the shelf tag or the fridge label. We read the price for you.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    PrimaryButton("Take photo") { shoot() }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                    ) { Text("Choose from gallery") }
                    ocrText?.let {
                        Spacer(Modifier.height(8.dp))
                        Text("Read from the tag:", style = MaterialTheme.typography.labelSmall)
                        Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                AddMode.Manual -> Spacer(Modifier.height(0.dp))
            }

            SectionCard(title = "What did you find?") {
                if (product != null) {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text(product!!.label(), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { productPicker = true }) { Text("Change") }
                    }
                } else {
                    OutlinedButton(
                        onClick = { productPicker = true },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                    ) { Text("Choose drink") }
                }
                Spacer(Modifier.height(10.dp))
                MoneyField(priceText, { priceText = it }, "Price on the tag")
                Spacer(Modifier.height(10.dp))
                if (store != null) {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(store!!.name, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                listOfNotNull(store!!.chainName, store!!.suburb, store!!.distanceKm?.let { Price.km(it) })
                                    .joinToString(" · "),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { storePicker = true }) { Text("Change") }
                    }
                } else {
                    OutlinedButton(
                        onClick = { storePicker = true },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                    ) { Text("Which store are you in?") }
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("none" to "Normal price", "special" to "Special", "clearance" to "Clearance").forEach { (key, label) ->
                        FilterChip(selected = promo == key, onClick = { promo = key }, label = { Text(label) })
                    }
                }
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it.take(300) },
                    label = { Text("Note (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                PrimaryButton(if (busy) "Saving…" else "Submit price", enabled = !busy) { submit() }
            }

            status?.let {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Text(it, Modifier.padding(14.dp), style = MaterialTheme.typography.bodySmall)
                }
            }
            ErrorBar(error)
            Spacer(Modifier.height(70.dp))
        }
    }

    if (productPicker) {
        ProductPickerDialog(
            title = "Which drink?",
            onDismiss = { productPicker = false },
            onPick = {
                product = it
                productPicker = false
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
    if (pendingDialog && unknownBarcode != null) {
        NewProductDialog(
            barcode = unknownBarcode!!,
            api = api,
            prefs = prefs,
            onDismiss = { pendingDialog = false },
            onDone = { message ->
                pendingDialog = false
                status = message
            },
        )
    }
}

@Composable
private fun NewProductDialog(
    barcode: String,
    api: Api,
    prefs: Prefs,
    onDismiss: () -> Unit,
    onDone: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var brand by remember { mutableStateOf("") }
    var size by remember { mutableStateOf("") }
    var price by remember { mutableStateOf("") }
    var store by remember { mutableStateOf<Store?>(null) }
    var storePicker by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New can") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Barcode " + barcode + " is not in the catalogue yet. Type what is printed on the can and it will be added for review.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(160) },
                    label = { Text("Name on the can") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
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
                        onValueChange = { size = it.filter { ch -> ch.isDigit() } },
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
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall) }
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
                        }.onSuccess {
                            onDone("Added " + name + " to the catalogue. Thanks!")
                        }.onFailure { error = it.message }
                        busy = false
                    }
                },
            ) { Text("Add") }
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
