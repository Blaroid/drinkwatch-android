package com.drinkwatch.app.data

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

class ApiException(message: String, val code: Int = 0) : Exception(message)

/**
 * Thin OkHttp wrapper over the DrinkWatch API.
 *
 * JSON is parsed by hand with org.json: the payloads are small, and this keeps the
 * app free of serialization plugins/codegen.
 */
class Api(
    private val prefs: Prefs,
    /** Where the response cache lives; null disables caching (tests). */
    cacheDir: java.io.File? = null,
) {

    private val transport = HttpTransport(cacheDir)
    private val client = transport.http

    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private val jpegType = "image/jpeg".toMediaType()

    // ---------------------------------------------------------------- plumbing

    private fun URL(path: String, params: Map<String, String?>): okhttp3.HttpUrl {
        val builder = (prefs.baseUrl + path).toHttpUrlOrNull()
            ?: throw ApiException("Bad API base URL: " + prefs.baseUrl)
        val url = builder.newBuilder()
        params.forEach { (key, value) -> if (value != null) url.addQueryParameter(key, value) }
        return url.build()
    }

    private fun execute(request: Request): String = transport.call(request).use { response ->
        val body = response.body?.string() ?: ""
        if (!response.isSuccessful) {
            val detail = runCatching { JSONObject(body).optString("detail") }.getOrNull()
                ?.takeIf { it.isNotBlank() && it != "null" }
                ?: body.take(180).ifBlank { response.message }
            throw ApiException(detail, response.code)
        }
        body
    }

    private fun authHeader(request: Request.Builder, required: Boolean): Request.Builder {
        request.header("User-Agent", "DrinkWatch-Android/0.1")
        if (!required) return request
        val token = prefs.token
        if (token.isNullOrBlank()) throw ApiException("No device token yet", 401)
        return request.header("Authorization", "Bearer " + token)
    }

    private suspend fun getJson(
        path: String,
        params: Map<String, String?> = emptyMap(),
        auth: Boolean = false,
    ): JSONObject = withContext(Dispatchers.IO) {
        val request = authHeader(Request.Builder().url(URL(path, params)).get(), auth).build()
        JSONObject(execute(request))
    }

    private suspend fun getArray(
        path: String,
        params: Map<String, String?> = emptyMap(),
        auth: Boolean = false,
    ): JSONArray = withContext(Dispatchers.IO) {
        val request = authHeader(Request.Builder().url(URL(path, params)).get(), auth).build()
        JSONArray(execute(request))
    }

    private suspend fun postJson(
        path: String,
        body: JSONObject,
        auth: Boolean = true,
        params: Map<String, String?> = emptyMap(),
    ): JSONObject = withContext(Dispatchers.IO) {
        val request = authHeader(
            Request.Builder().url(URL(path, params)).post(body.toString().toRequestBody(jsonType)),
            auth,
        ).build()
        JSONObject(execute(request))
    }

    private suspend fun deleteJson(path: String): JSONObject = withContext(Dispatchers.IO) {
        val request = authHeader(Request.Builder().url(URL(path, emptyMap())).delete(), true).build()
        JSONObject(execute(request))
    }

    // ---------------------------------------------------------------- device

    private val deviceMutex = Mutex()

    suspend fun ensureDevice(): String {
        prefs.token?.takeIf { it.isNotBlank() }?.let { return it }
        return deviceMutex.withLock {
            prefs.token?.takeIf { it.isNotBlank() } ?: registerDevice()
        }
    }

    private suspend fun registerDevice(): String {
        val payload = JSONObject()
            .put("platform", "android")
            .put("app_version", com.drinkwatch.app.BuildConfig.VERSION_NAME)
            .put("display_name", prefs.displayName.ifBlank { JSONObject.NULL })
        val response = postJson("/v1/devices", payload, auth = false)
        val token = response.getString("token")
        prefs.token = token
        prefs.deviceId = response.optInt("id", 0)
        return token
    }

    // ---------------------------------------------------------------- reads

    suspend fun widget(
        lat: Double,
        lon: Double,
        radiusKm: Int,
        slugs: List<String>,
        suggest: Int = 0,
    ): JSONObject = getJson(
        "/v1/widget",
        mapOf(
            "lat" to lat.toString(),
            "lon" to lon.toString(),
            "radius_km" to radiusKm.toString(),
            "products" to slugs.takeIf { it.isNotEmpty() }?.joinToString(","),
            "suggest" to suggest.takeIf { it > 0 }?.toString(),
        ),
    )

    /**
     * Widget rows, surviving tracked drinks that were renamed or merged server-side.
     *
     * /v1/widget 404s when any requested slug is gone and names only the first
     * offender, so a list with two dead drinks needs two passes. Only that slug is
     * removed and the pruned list is persisted plus explained through
     * [Prefs.trackedNote]: the tracked list is the user's only personal state, so it
     * must never be thrown away wholesale.
     *
     * @param cache store the payload as the widget's offline copy. The worker owns
     *   this; the home screen's payload carries suggestion rows the widget should
     *   not show.
     */
    suspend fun widgetItems(
        lat: Double,
        lon: Double,
        radiusKm: Int,
        slugs: List<String>,
        suggest: Int = 0,
        cache: Boolean = false,
    ): List<WidgetItem> {
        val (payload, dropped) = recoveringWidget(lat, lon, radiusKm, slugs, suggest)

        if (dropped.isNotEmpty()) {
            // The response that dropped a slug no longer mentions it, so its name
            // can only come from the previous payload.
            val labels = itemsFromCache(prefs.widgetCache).associate { it.slug to it.label }
            // Remove only what went stale: the user may have edited the list while
            // the request was in flight.
            prefs.trackedSlugs = prefs.trackedSlugs.filterNot { it in dropped }
            staleNote(dropped, labels)?.let { prefs.trackedNote = it }
        }
        if (cache) {
            prefs.widgetCache = payload.toString()
            prefs.widgetCacheAt = System.currentTimeMillis()
        }

        val items = payload.optJSONArray("items") ?: JSONArray()
        return (0 until items.length()).map { items.getJSONObject(it).toWidgetItem() }
    }

    /** The widget payload plus the slugs a 404 recovery had to remove to get it. */
    private suspend fun recoveringWidget(
        lat: Double,
        lon: Double,
        radiusKm: Int,
        slugs: List<String>,
        suggest: Int,
    ): Pair<JSONObject, List<String>> {
        var wanted = slugs
        val dropped = mutableListOf<String>()
        var giveUp = false
        while (true) {
            val ask = if (giveUp) emptyList() else wanted
            try {
                val payload = widget(
                    lat, lon, radiusKm, ask,
                    suggest = if (ask.isEmpty()) 0 else suggest,
                )
                return payload to dropped
            } catch (error: ApiException) {
                if (error.code != 404 || ask.isEmpty()) throw error
                val pruned = pruneStale(ask, error.message)
                if (pruned == ask) {
                    // Cannot tell which drink the server objects to. Show the default
                    // list for this run but keep the stored one: deleting the wrong
                    // drink is worse than a fallback, and the catalogue may come back.
                    giveUp = true
                } else {
                    dropped.addAll(ask.filterNot { it in pruned })
                    wanted = pruned
                }
            }
        }
    }

    suspend fun prices(slug: String, lat: Double, lon: Double, radiusKm: Int): ProductPrices {
        val payload = getJson(
            "/v1/prices",
            mapOf("product" to slug, "lat" to lat.toString(), "lon" to lon.toString(), "radius_km" to radiusKm.toString()),
        )
        val offers = payload.optJSONArray("offers") ?: JSONArray()
        return ProductPrices(
            product = payload.getJSONObject("product").toProduct(),
            offers = (0 until offers.length()).map { offers.getJSONObject(it).toOffer() },
        )
    }

    suspend fun searchProducts(query: String, limit: Int = 20): List<Product> {
        val array = getArray("/v1/products", mapOf("q" to query.ifBlank { null }, "limit" to limit.toString()))
        return (0 until array.length()).map { array.getJSONObject(it).toProduct() }
    }

    suspend fun barcode(code: String): BarcodeHit {
        val payload = getJson("/v1/barcode/" + code)
        val suggestion = payload.optJSONObject("suggestion")
        return BarcodeHit(
            found = payload.optBoolean("found", false),
            barcode = payload.optString("barcode"),
            normalised = payload.str("normalised"),
            checksumOk = payload.optBoolean("checksum_ok", false),
            product = payload.optJSONObject("product")?.toProduct(),
            suggestedName = suggestion?.str("name"),
            suggestedBrand = suggestion?.str("brand"),
            pendingHint = payload.str("pending_hint"),
        )
    }

    suspend fun storesNearby(lat: Double, lon: Double, radiusKm: Int, limit: Int = 30): List<Store> {
        val array = getArray(
            "/v1/stores/nearby",
            mapOf("lat" to lat.toString(), "lon" to lon.toString(), "radius_km" to radiusKm.toString(), "limit" to limit.toString()),
        )
        return (0 until array.length()).map { array.getJSONObject(it).toStore() }
    }

    suspend fun searchStores(query: String, lat: Double, lon: Double): List<Store> {
        val array = getArray(
            "/v1/stores/search",
            mapOf("q" to query, "lat" to lat.toString(), "lon" to lon.toString(), "limit" to "25"),
        )
        return (0 until array.length()).map { array.getJSONObject(it).toStore() }
    }

    // ---------------------------------------------------------------- writes

    suspend fun uploadPhoto(bytes: ByteArray): String = withContext(Dispatchers.IO) {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", "tag.jpg", bytes.toRequestBody(jpegType))
            .build()
        val request = authHeader(Request.Builder().url(URL("/v1/photos", emptyMap())).post(body), true).build()
        JSONObject(execute(request)).getString("token")
    }

    suspend fun submitObservation(
        storeId: Int,
        productSlug: String,
        priceCents: Int,
        promoKind: String = "none",
        promoQty: Int? = null,
        promoTotalCents: Int? = null,
        note: String? = null,
        photoToken: String? = null,
    ): JSONObject {
        val payload = JSONObject()
            .put("store_id", storeId)
            .put("product_slug", productSlug)
            .put("price_cents", priceCents)
            .put("promo_kind", promoKind)
        promoQty?.let { payload.put("promo_qty", it) }
        promoTotalCents?.let { payload.put("promo_total_cents", it) }
        note?.takeIf { it.isNotBlank() }?.let { payload.put("note", it) }
        photoToken?.let { payload.put("photo_token", it) }
        return postJson("/v1/observations", payload)
    }

    suspend fun submitPending(
        barcode: String,
        name: String,
        brand: String?,
        sizeMl: Int?,
        storeId: Int?,
        priceCents: Int?,
        photoToken: String?,
    ): JSONObject {
        val payload = JSONObject().put("barcode", barcode).put("name", name)
        brand?.takeIf { it.isNotBlank() }?.let { payload.put("brand", it) }
        sizeMl?.let { payload.put("size_ml", it) }
        storeId?.let { payload.put("store_id", it) }
        priceCents?.let { payload.put("price_cents", it) }
        photoToken?.let { payload.put("photo_token", it) }
        return postJson("/v1/products/pending", payload)
    }

    suspend fun compareBasket(
        items: List<BasketRequestItem>,
        lat: Double,
        lon: Double,
        radiusKm: Int,
    ): BasketResult {
        val entries = JSONArray()
        items.forEach { entries.put(JSONObject().put("slug", it.slug).put("quantity", it.quantity)) }
        val payload = JSONObject()
            .put("items", entries)
            .put("lat", lat)
            .put("lon", lon)
            .put("radius_km", radiusKm)
        return postJson("/v1/basket/compare", payload, auth = false).toBasketResult()
    }

    suspend fun maxCaffeine(
        budgetCents: Int,
        lat: Double,
        lon: Double,
        radiusKm: Int,
        multiStore: Boolean,
    ): MaxCaffeine = getJson(
        "/v1/value/max-caffeine",
        mapOf(
            "budget_cents" to budgetCents.toString(),
            "lat" to lat.toString(),
            "lon" to lon.toString(),
            "radius_km" to radiusKm.toString(),
            "mode" to if (multiStore) "multi_store" else "single_store",
        ),
    ).toMaxCaffeine()

    suspend fun routePlan(items: List<BasketRequestItem>, lat: Double, lon: Double, radiusKm: Int): RoutePlan {
        val spec = items.joinToString(",") { it.slug + ":" + it.quantity }
        return getJson(
            "/v1/plan/route",
            mapOf("items" to spec, "lat" to lat.toString(), "lon" to lon.toString(), "radius_km" to radiusKm.toString()),
        ).toRoutePlan()
    }

    // ---------------------------------------------------------------- alerts

    suspend fun alerts(): List<PriceAlert> {
        val array = getArray("/v1/alerts", auth = true)
        return (0 until array.length())
            .map { index ->
                val row = array.getJSONObject(index)
                PriceAlert(
                    id = row.getInt("id"),
                    productId = if (row.isNull("product_id")) null else row.getInt("product_id"),
                    familySlug = row.str("family_slug"),
                    chainCode = row.str("chain_code"),
                    storeId = if (row.isNull("store_id")) null else row.getInt("store_id"),
                    maxPriceCents = row.getInt("max_price_cents"),
                    isActive = row.optBoolean("is_active", true),
                    createdAt = row.optString("created_at"),
                )
            }
            .filter { it.isActive }
    }

    suspend fun createAlert(slug: String, maxPriceCents: Int) {
        postJson("/v1/alerts", JSONObject().put("product_slug", slug).put("max_price_cents", maxPriceCents))
    }

    suspend fun deleteAlert(id: Int) {
        deleteJson("/v1/alerts/" + id)
    }

    suspend fun notifications(): List<AppNotification> {
        val array = getArray("/v1/notifications", mapOf("limit" to "50"), auth = true)
        return (0 until array.length()).map {
            val row = array.getJSONObject(it)
            AppNotification(
                id = row.getInt("id"),
                kind = row.optString("kind"),
                title = row.optString("title"),
                body = row.optString("body"),
                readAt = row.str("read_at"),
                createdAt = row.optString("created_at"),
            )
        }
    }

    suspend fun markNotificationRead(id: Int) {
        postJson("/v1/notifications/" + id + "/read", JSONObject())
    }

    // ---------------------------------------------------------------- caffeine

    suspend fun caffeineToday(): CaffeineToday {
        val payload = getJson("/v1/caffeine/today", auth = true)
        val entries = payload.optJSONArray("entries") ?: JSONArray()
        return CaffeineToday(
            date = payload.optString("date"),
            totalMg = payload.optInt("total_mg"),
            limitMg = payload.optInt("limit_mg", 400),
            remainingMg = payload.optInt("remaining_mg"),
            entries = (0 until entries.length()).map {
                val row = entries.getJSONObject(it)
                CaffeineEntry(
                    id = row.getInt("id"),
                    label = row.str("label"),
                    qty = row.optInt("qty", 1),
                    mg = row.optInt("mg_caffeine"),
                    consumedAt = row.optString("consumed_at"),
                )
            },
        )
    }

    suspend fun logCaffeine(productSlug: String?, label: String?, mg: Int?, qty: Int = 1) {
        val payload = JSONObject().put("qty", qty)
        when {
            productSlug != null -> payload.put("product_slug", productSlug)
            mg != null -> payload.put("mg_caffeine", mg).put("label", label ?: "drink")
            else -> payload.put("mg_caffeine", 0).put("label", label ?: "drink")
        }
        postJson("/v1/caffeine", payload)
    }

    suspend fun health(): JSONObject = getJson("/healthz")

    /** Latest published build, from /app/version.json (no auth, outside /v1). */
    suspend fun latestAppVersion(): AppUpdate = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(prefs.baseUrl + "/app/version.json")
            .header("User-Agent", "DrinkWatch-Android/0.1")
            .get()
            .build()
        val payload = JSONObject(execute(request))
        AppUpdate(
            versionCode = payload.optInt("versionCode", 0),
            versionName = payload.optString("versionName", "?"),
            notes = payload.optString("notes", ""),
            apkUrl = payload.optString("apk").ifBlank { prefs.baseUrl + "/app/drinkwatch.apk" },
        )
    }

    /** Stream the APK to disk, reporting 0..1 progress when the size is known. */
    suspend fun downloadApk(url: String, target: java.io.File, onProgress: (Float?) -> Unit): java.io.File =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "DrinkWatch-Android/0.1")
                .get()
                .build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                response.close()
                throw ApiException("Download failed: HTTP " + response.code, response.code)
            }
            val body = response.body ?: throw ApiException("Empty download")
            val total = body.contentLength()
            target.parentFile?.mkdirs()
            body.byteStream().use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var copied = 0L
                    var lastReport = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        copied += read
                        if (total > 0 && copied - lastReport > 512 * 1024) {
                            lastReport = copied
                            onProgress((copied.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                }
            }
            onProgress(1f)
            target
        }

    companion object {
        private const val TAG = "Api"

        /** Parse a previously cached /v1/widget payload (offline fallback). */
        fun itemsFromCache(json: String?): List<WidgetItem> {
            if (json.isNullOrBlank()) return emptyList()
            return runCatching {
                val items = JSONObject(json).optJSONArray("items") ?: JSONArray()
                (0 until items.length()).map { items.getJSONObject(it).toWidgetItem() }
            }.getOrDefault(emptyList())
        }

        fun logError(where: String, throwable: Throwable) {
            Log.w(TAG, where + " failed: " + throwable.message)
        }
    }
}

// ------------------------------------------------------- stale tracked slugs

/**
 * Drop the one slug the server rejected, or keep the list untouched when the
 * detail does not name a slug we actually sent.
 *
 * The API answers `404 {"detail": "unknown product slug: <slug>"}`; taking the
 * tail after the last colon rather than the exact wording means a reworded server
 * message still recovers, while the membership check stops a truncated body from
 * deleting an innocent drink.
 */
internal fun pruneStale(slugs: List<String>, detail: String?): List<String> {
    val named = detail?.trim()?.substringAfterLast(':')?.trim().orEmpty()
    if (named.isEmpty() || named !in slugs) return slugs
    return slugs.filterNot { it == named }
}

/**
 * What the home screen says after a stale slug was dropped: the drink's name from
 * the last payload when we still have it, otherwise the slug with the dashes
 * opened up ("v-blue" -> "v blue"). Null when nothing was dropped.
 */
internal fun staleNote(dropped: List<String>, labels: Map<String, String>): String? {
    if (dropped.isEmpty()) return null
    val names = dropped.map { slug ->
        labels[slug]?.takeIf { it.isNotBlank() } ?: slug.replace('-', ' ')
    }
    val listed = when (names.size) {
        1 -> names[0]
        2 -> names.joinToString(" and ")
        else -> names.dropLast(1).joinToString(", ") + " and " + names.last()
    }
    val verb = if (names.size == 1) " is" else " are"
    return "$listed$verb no longer listed — removed from your drinks"
}

// -------------------------------------------------------------------- parsing

internal fun JSONObject.str(key: String): String? =
    if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() && it != "null" }

internal fun JSONObject.toStore(): Store {
    val chain = optJSONObject("chain")
    return Store(
        id = getInt("id"),
        name = optString("name"),
        suburb = str("suburb"),
        postcode = str("postcode"),
        lat = if (isNull("lat")) null else optDouble("lat"),
        lon = if (isNull("lon")) null else optDouble("lon"),
        chainCode = chain?.optString("code") ?: "",
        chainName = chain?.optString("name") ?: "",
        chainKind = chain?.optString("kind") ?: "supermarket",
        distanceKm = if (isNull("distance_km")) null else optDouble("distance_km"),
    )
}

internal fun JSONObject.toProduct(): Product = Product(
    id = getInt("id"),
    slug = optString("slug"),
    brand = optString("brand"),
    name = optString("name"),
    variant = str("variant"),
    sizeMl = if (isNull("size_ml")) null else optInt("size_ml"),
    packCount = optInt("pack_count", 1),
    barcode = str("barcode"),
    category = optString("category", "energy"),
)

internal fun JSONObject.toOffer(): Offer {
    val stats = optJSONObject("stats")
    return Offer(
        storeId = getInt("store_id"),
        storeName = optString("store_name"),
        chainCode = optString("chain_code"),
        chainName = optString("chain_name"),
        chainKind = optString("chain_kind"),
        suburb = str("suburb"),
        distanceKm = if (isNull("distance_km")) null else optDouble("distance_km"),
        priceCents = getInt("price_cents"),
        wasPriceCents = if (isNull("was_price_cents")) null else optInt("was_price_cents"),
        perCanCents = if (isNull("per_can_cents")) null else optInt("per_can_cents"),
        perLitreCents = if (isNull("per_litre_cents")) null else optInt("per_litre_cents"),
        promoKind = optString("promo_kind", "none"),
        promoQty = if (isNull("promo_qty")) null else optInt("promo_qty"),
        promoTotalCents = if (isNull("promo_total_cents")) null else optInt("promo_total_cents"),
        inStock = if (isNull("in_stock")) null else optBoolean("in_stock"),
        source = optString("source"),
        priceScope = optString("price_scope"),
        ageHours = optDouble("age_hours", 0.0),
        stats = stats?.let {
            DealStats(
                median90dCents = if (it.isNull("median_90d_cents")) null else it.optInt("median_90d_cents"),
                min90dCents = if (it.isNull("min_90d_cents")) null else it.optInt("min_90d_cents"),
                deal = it.optString("deal", "unknown"),
            )
        },
    )
}

internal fun JSONObject.toWidgetItem(): WidgetItem = WidgetItem(
    slug = optString("slug"),
    label = optString("label"),
    priceCents = getInt("price_cents"),
    perCanCents = if (isNull("per_can_cents")) null else optInt("per_can_cents"),
    store = optString("store"),
    chain = optString("chain"),
    suburb = str("suburb"),
    distanceKm = if (isNull("distance_km")) null else optDouble("distance_km"),
    promo = optString("promo", "none"),
    source = optString("source"),
    ageHours = optDouble("age_hours", 0.0),
    suggested = optBoolean("suggested", false),
)

internal fun JSONObject.toBasketResult(): BasketResult {
    val stores = optJSONArray("stores") ?: JSONArray()
    val plan = optJSONArray("multi_store_plan") ?: JSONArray()
    return BasketResult(
        radiusKm = optDouble("radius_km", 0.0),
        stores = (0 until stores.length()).map { index ->
            val store = stores.getJSONObject(index)
            val items = store.optJSONArray("items") ?: JSONArray()
            BasketStore(
                storeId = store.getInt("store_id"),
                storeName = store.optString("store_name"),
                chainCode = store.optString("chain_code"),
                suburb = store.str("suburb"),
                distanceKm = store.optDouble("distance_km", 0.0),
                totalCents = store.getInt("total_cents"),
                complete = store.optBoolean("complete", false),
                items = (0 until items.length()).map { itemIndex ->
                    val line = items.getJSONObject(itemIndex)
                    val breakdown = line.optJSONArray("breakdown") ?: JSONArray()
                    BasketLine(
                        requested = line.optString("requested"),
                        quantity = line.optInt("quantity"),
                        productSlug = line.str("product_slug"),
                        label = line.str("label"),
                        units = line.optInt("units"),
                        costCents = line.optInt("cost_cents"),
                        breakdown = (0 until breakdown.length()).map { breakdown.getString(it) },
                    )
                },
            )
        },
        multiStoreTotalCents = if (isNull("multi_store_total_cents")) null else optInt("multi_store_total_cents"),
        multiStorePlan = (0 until plan.length()).map { index ->
            val line = plan.getJSONObject(index)
            BasketPlanLine(
                requested = line.optString("requested"),
                quantity = line.optInt("quantity"),
                storeName = line.optString("store_name"),
                chainCode = line.optString("chain_code"),
                costCents = line.optInt("cost_cents"),
            )
        },
        savingsVsWorstCents = if (isNull("savings_vs_worst_cents")) null else optInt("savings_vs_worst_cents"),
    )
}

internal fun JSONObject.toMaxCaffeine(): MaxCaffeine {
    val items = optJSONArray("items") ?: JSONArray()
    return MaxCaffeine(
        budgetCents = getInt("budget_cents"),
        mode = optString("mode"),
        spentCents = optInt("spent_cents"),
        leftoverCents = optInt("leftover_cents"),
        totalCaffeineMg = optInt("total_caffeine_mg"),
        totalMl = optInt("total_ml"),
        items = (0 until items.length()).map { index ->
            val row = items.getJSONObject(index)
            BudgetItem(
                slug = row.optString("slug"),
                label = row.optString("label"),
                storeName = row.optString("store_name"),
                chainCode = row.optString("chain_code"),
                units = row.optInt("units"),
                costCents = row.optInt("cost_cents"),
                caffeineMg = row.optInt("caffeine_mg"),
            )
        },
        note = optString("note"),
        dailyLimitMg = optInt("daily_limit_mg", 400),
        exceedsDailyLimit = optBoolean("exceeds_daily_limit", false),
        storesUsed = optInt("stores_used", 1),
    )
}

internal fun JSONObject.toRoutePlan(): RoutePlan {
    val stops = optJSONArray("stops") ?: JSONArray()
    return RoutePlan(
        stops = (0 until stops.length()).map { index ->
            val row = stops.getJSONObject(index)
            RouteStop(
                storeId = row.getInt("store_id"),
                storeName = row.optString("store_name"),
                chainCode = row.optString("chain_code"),
                suburb = row.str("suburb"),
                lat = if (row.isNull("lat")) null else row.optDouble("lat"),
                lon = if (row.isNull("lon")) null else row.optDouble("lon"),
                subtotalCents = row.optInt("subtotal_cents"),
                distanceFromOriginKm = if (row.isNull("distance_from_origin_km")) null else row.optDouble("distance_from_origin_km"),
            )
        },
        itemsTotalCents = optInt("items_total_cents"),
        travelKm = optDouble("travel_km", 0.0),
        fuelPriceCentsPerL = if (isNull("fuel_price_cents_per_l")) null else optInt("fuel_price_cents_per_l"),
        fuelCostCents = optInt("fuel_cost_cents"),
        totalCostCents = optInt("total_cost_cents"),
        singleStoreBaselineCents = if (isNull("single_store_baseline_cents")) null else optInt("single_store_baseline_cents"),
        netSavingCents = if (isNull("net_saving_cents")) null else optInt("net_saving_cents"),
        fuelSource = str("fuel_source"),
    )
}
