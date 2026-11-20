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
import java.time.Instant
import java.time.OffsetDateTime

class ApiException(message: String, val code: Int = 0) : Exception(message)

/**
 * What the user sees when a token registered a moment ago is refused as well.
 *
 * The server detail ("unknown device token; register first") names an internal
 * concept and suggests nothing the user can do, so it is replaced once the
 * automatic recovery has already been tried.
 */
private const val EXPIRED_DEVICE = "Device identity expired — tap Retry to register again"

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

    /** A response body plus whether it came from the transport's offline fallback. */
    private class Fetched(val body: String, val stale: Boolean)

    private fun execute(request: Request): Fetched = transport.call(request).use { result ->
        val response = result.response
        val body = response.body?.string() ?: ""
        if (!response.isSuccessful) {
            throw ApiException(errorMessage(body, response.message), response.code)
        }
        Fetched(body, result.stale)
    }

    /**
     * Run one request, healing a device token the server no longer knows.
     *
     * A restored backup, a rebuilt backend or a deleted device row leaves a token
     * the API answers `401 {"detail":"unknown device token; register first"}` to,
     * which used to leave alerts, the caffeine log and crowd submissions dead until
     * the user found Settings -> "Reset device identity". Dropping the stored
     * identity and registering once is exactly what that button does, so the app can
     * do it itself; [deviceRecovery] keeps the second 401 from turning into a loop.
     */
    private suspend fun <T> withDevice(auth: Boolean, block: suspend () -> T): T {
        var attempt = 0
        while (true) {
            val attempted = prefs.token
            try {
                return block()
            } catch (error: ApiException) {
                when (deviceRecovery(error.code, attempt, auth)) {
                    DeviceRecovery.Fail -> throw error
                    DeviceRecovery.ReRegister -> {
                        renewDevice(attempted)
                        attempt++
                    }
                    DeviceRecovery.Expired -> throw ApiException(EXPIRED_DEVICE, error.code)
                }
            }
        }
    }

    /**
     * Forget the token the server rejected and register a new one.
     *
     * Compared inside the mutex because several requests can hit 401 at once: only
     * the first should re-register, the rest retry with the token it just stored
     * rather than orphaning it with a second device.
     */
    private suspend fun renewDevice(rejected: String?): String = deviceMutex.withLock {
        val current = prefs.token?.takeIf { it.isNotBlank() }
        if (current != null && current != rejected) return@withLock current
        prefs.token = null
        prefs.deviceId = 0
        registerDevice()
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
    ): JSONObject = JSONObject(getFetched(path, params, auth).body)

    /**
     * [getJson] that also reports whether the body is the offline cache copy.
     * /v1/widget is the only endpoint that has to tell the two apart.
     */
    private suspend fun getFetched(
        path: String,
        params: Map<String, String?> = emptyMap(),
        auth: Boolean = false,
    ): Fetched = withDevice(auth) {
        withContext(Dispatchers.IO) {
            val request = authHeader(Request.Builder().url(URL(path, params)).get(), auth).build()
            execute(request)
        }
    }

    private suspend fun getArray(
        path: String,
        params: Map<String, String?> = emptyMap(),
        auth: Boolean = false,
    ): JSONArray = withDevice(auth) {
        withContext(Dispatchers.IO) {
            val request = authHeader(Request.Builder().url(URL(path, params)).get(), auth).build()
            JSONArray(execute(request).body)
        }
    }

    private suspend fun postJson(
        path: String,
        body: JSONObject,
        auth: Boolean = true,
        params: Map<String, String?> = emptyMap(),
    ): JSONObject = withDevice(auth) {
        withContext(Dispatchers.IO) {
            val request = authHeader(
                Request.Builder().url(URL(path, params)).post(body.toString().toRequestBody(jsonType)),
                auth,
            ).build()
            JSONObject(execute(request).body)
        }
    }

    private suspend fun deleteJson(path: String): JSONObject = withDevice(true) {
        withContext(Dispatchers.IO) {
            val request = authHeader(Request.Builder().url(URL(path, emptyMap())).delete(), true).build()
            JSONObject(execute(request).body)
        }
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

    private suspend fun widget(
        lat: Double,
        lon: Double,
        radiusKm: Int,
        slugs: List<String>,
        suggest: Int = 0,
    ): Fetched = getFetched(
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
     * Widget rows for the whole watchlist, surviving drinks renamed or merged server-side.
     *
     * /v1/widget keeps only the first eight `products` slugs and answers 200 anyway, so
     * a longer watchlist silently lost the drinks followed most recently. Asking in
     * batches of [WIDGET_BATCH] lifts the limit instead of advertising it: no watchlist
     * size is refused, so nothing has to warn the user to stop following drinks.
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
        val batches = widgetBatches(slugs)
        // Suggestions go on the last batch so the server appends them after every
        // tracked row; asked for on the first one they would land in the middle of the
        // list, above the drinks still waiting to be fetched.
        val fetches = batches.mapIndexed { index, batch ->
            recoveringWidget(
                lat, lon, radiusKm, batch,
                suggest = if (index == batches.lastIndex) suggest else 0,
            )
        }
        val dropped = fetches.flatMap { it.dropped }

        if (dropped.isNotEmpty()) {
            // The response that dropped a slug no longer mentions it, so its name
            // can only come from the previous payload.
            val labels = itemsFromCache(prefs.widgetCache).associate { it.slug to it.label }
            // Remove only what went stale: the user may have edited the list while
            // the request was in flight, so the filter is applied to whatever the list
            // is now rather than to the copy this call started with.
            prefs.updateTracked { current -> current.filterNot { it in dropped } }
            staleNote(dropped, labels)?.let { prefs.trackedNote = it }
        }

        val payload = mergedWidgetPayload(fetches.map { it.payload })
        if (cache) {
            // The widget shows this copy, so keep it in step with what we just
            // parsed; only an answer from the server may call it fresh. A stale
            // copy served during an outage must not reset the timestamp, or the
            // widget would present month-old prices as a refresh from minutes ago
            // and the worker's 6-hour recovery test could never fire. Every batch has
            // to be live for that reason: one replayed from the cache would leave rows
            // in the merged payload that the timestamp claims were fetched just now.
            prefs.widgetCache = payload.toString()
            if (fetches.all { it.live }) prefs.widgetCacheAt = System.currentTimeMillis()
        }
        return payload.toWidgetItems()
    }

    /** The widget payload, the slugs a 404 recovery had to drop, and whether a server answered. */
    private class WidgetFetch(val payload: JSONObject, val dropped: List<String>, val live: Boolean)

    /** The widget payload plus the slugs a 404 recovery had to remove to get it. */
    private suspend fun recoveringWidget(
        lat: Double,
        lon: Double,
        radiusKm: Int,
        slugs: List<String>,
        suggest: Int,
    ): WidgetFetch {
        var wanted = slugs
        val dropped = mutableListOf<String>()
        var giveUp = false
        while (true) {
            val ask = if (giveUp) emptyList() else wanted
            try {
                val fetched = widget(
                    lat, lon, radiusKm, ask,
                    suggest = if (ask.isEmpty()) 0 else suggest,
                )
                return WidgetFetch(JSONObject(fetched.body), dropped, live = !fetched.stale)
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

    suspend fun prices(
        slug: String,
        lat: Double,
        lon: Double,
        radiusKm: Int,
        sort: String = "price",
    ): ProductPrices {
        val payload = getJson(
            "/v1/prices",
            mapOf(
                "product" to slug,
                "lat" to lat.toString(),
                "lon" to lon.toString(),
                "radius_km" to radiusKm.toString(),
                "sort" to sort,
            ),
        )
        val offers = payload.optJSONArray("offers") ?: JSONArray()
        return ProductPrices(
            product = payload.getJSONObject("product").toProduct(),
            offers = (0 until offers.length()).map { offers.getJSONObject(it).toOffer() },
        )
    }

    /**
     * Catalogue search.
     *
     * `strict` is what the add picker asks for: every typed word must begin a
     * word in the title, with no fuzzy near-miss, so a typo shows nothing rather
     * than another drink. Elsewhere (the tag prefill) a forgiving match is fine.
     */
    suspend fun searchProducts(query: String, limit: Int = 20, strict: Boolean = false): List<Product> {
        val array = getArray(
            "/v1/products",
            mapOf(
                "q" to query.ifBlank { null },
                "limit" to limit.toString(),
                "strict" to if (strict) "true" else null,
            ),
        )
        return (0 until array.length()).map { array.getJSONObject(it).toProduct() }
    }

    /** Other sizes and carton counts of the same flavour, for the add picker. */
    suspend fun productVariants(slug: String): List<Product> {
        // Slugs are lowercase alnum/dash, so they need no query encoding.
        val array = getArray("/v1/products/" + slug + "/variants")
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

    suspend fun uploadPhoto(bytes: ByteArray): String = withDevice(true) {
        withContext(Dispatchers.IO) {
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", "tag.jpg", bytes.toRequestBody(jpegType))
                .build()
            val request = authHeader(Request.Builder().url(URL("/v1/photos", emptyMap())).post(body), true).build()
            JSONObject(execute(request).body).getString("token")
        }
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
            .map { array.getJSONObject(it).toPriceAlert() }
            .filter { it.isActive }
    }

    suspend fun createAlert(request: AlertRequest) {
        val payload = JSONObject()
        request.toFields().forEach { (key, value) -> payload.put(key, value) }
        postJson("/v1/alerts", payload)
    }

    suspend fun deleteAlert(id: Int) {
        deleteJson("/v1/alerts/" + id)
    }

    /**
     * One page of alerts, newest first.
     *
     * The endpoint orders `created_at.desc()` and caps `limit` at 200, so the newest
     * rows are all one request can see; [Notifications.coverage] is what says whether
     * that page reached the push cursor.
     */
    suspend fun notifications(limit: Int = Notifications.PAGE_LIMIT): List<AppNotification> {
        val array = getArray("/v1/notifications", mapOf("limit" to limit.toString()), auth = true)
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
    suspend fun history(slug: String, lat: Double, lon: Double, radiusKm: Int, days: Int = 90): History {
        val payload = getJson(
            "/v1/history",
            mapOf(
                "product" to slug,
                "lat" to lat.toString(),
                "lon" to lon.toString(),
                "radius_km" to radiusKm.toString(),
                "days" to days.toString(),
            ),
        )
        val points = payload.optJSONArray("points") ?: JSONArray()
        return History(
            days = payload.optInt("days", days),
            points = (0 until points.length()).map { index ->
                val row = points.getJSONObject(index)
                HistoryPoint(
                    day = row.optString("day"),
                    priceCents = row.optInt("price_cents"),
                    storeName = row.str("store_name"),
                )
            },
            medianCents = if (payload.isNull("median_cents")) null else payload.optInt("median_cents"),
            minCents = if (payload.isNull("min_cents")) null else payload.optInt("min_cents"),
            maxCents = if (payload.isNull("max_cents")) null else payload.optInt("max_cents"),
        )
    }

    suspend fun deals(
        lat: Double,
        lon: Double,
        radiusKm: Int,
        metric: String = "per_litre",
        limit: Int = 20,
    ): List<Deal> {
        val array = getArray(
            "/v1/deals",
            mapOf(
                "lat" to lat.toString(),
                "lon" to lon.toString(),
                "radius_km" to radiusKm.toString(),
                "metric" to metric,
                "limit" to limit.toString(),
            ),
        )
        return (0 until array.length()).map { index ->
            val row = array.getJSONObject(index)
            val product = row.getJSONObject("product")
            Deal(
                slug = product.optString("slug"),
                label = listOf(product.optString("brand"), product.optString("name"))
                    .filter { it.isNotBlank() }.joinToString(" ") +
                    (if (product.isNull("size_ml")) "" else " ${product.optInt("size_ml")}ml"),
                storeName = row.optString("store_name"),
                chainCode = row.optString("chain_code"),
                distanceKm = if (row.isNull("distance_km")) null else row.optDouble("distance_km"),
                priceCents = row.optInt("price_cents"),
                perCanCents = if (row.isNull("per_can_cents")) null else row.optInt("per_can_cents"),
                perLitreCents = if (row.isNull("per_litre_cents")) null else row.optInt("per_litre_cents"),
                promoKind = row.optString("promo_kind", "none"),
                ageHours = row.optDouble("age_hours", 0.0),
            )
        }
    }

    suspend fun catalogue(): JSONObject = getJson("/v1/catalogue")

    suspend fun meta(): JSONObject = getJson("/v1/meta")

    /** Server-side matching of OCR receipt lines to catalogue products. */
    suspend fun parseReceipt(text: String): List<ReceiptCandidate> {
        val payload = JSONObject().put("text", text)
        val array = JSONArray(execute(
            authHeader(
                Request.Builder()
                    .url(URL("/v1/receipts/parse", emptyMap()))
                    .post(payload.toString().toRequestBody(jsonType)),
                true,
            ).build()
        ))
        return (0 until array.length()).map { index ->
            val row = array.getJSONObject(index)
            ReceiptCandidate(
                line = row.optString("line"),
                priceCents = if (row.isNull("price_cents")) null else row.optInt("price_cents"),
                productSlug = row.str("product_slug"),
                label = row.str("label"),
                score = row.optDouble("score", 0.0),
            )
        }
    }

    /** One request for a whole docket; the server charges the quota once. */
    suspend fun submitBatch(items: List<Triple<Int, String, Int>>): JSONObject {
        val entries = JSONArray()
        items.forEach { (storeId, slug, priceCents) ->
            entries.put(
                JSONObject()
                    .put("store_id", storeId)
                    .put("product_slug", slug)
                    .put("price_cents", priceCents)
            )
        }
        return postJson("/v1/observations/batch", JSONObject().put("items", entries))
    }

    /** "This price is still right" from the shop floor. */
    suspend fun confirmObservation(id: Int, lat: Double?, lon: Double?): JSONObject {
        val payload = JSONObject()
        if (lat != null) payload.put("lat", lat)
        if (lon != null) payload.put("lon", lon)
        return postJson("/v1/observations/" + id + "/confirm", payload)
    }

    suspend fun syncWatchlist(slugs: List<String>, lat: Double, lon: Double, radiusKm: Int) {
        postJson(
            "/v1/watchlist",
            JSONObject()
                .put("slugs", JSONArray(slugs))
                .put("lat", lat)
                .put("lon", lon)
                .put("radius_km", radiusKm),
        )
    }

    suspend fun latestAppVersion(): AppUpdate = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(prefs.baseUrl + "/app/version.json")
            .header("User-Agent", "DrinkWatch-Android/0.1")
            .get()
            .build()
        val payload = JSONObject(execute(request).body)
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
        fun itemsFromCache(json: String?): List<WidgetItem> = cachedWidget(json).items

        /**
         * The cached payload's rows plus the timestamp the server built it, which
         * is what lets the widget show how old its prices really are.
         */
        fun cachedWidget(json: String?): WidgetCache {
            if (json.isNullOrBlank()) return WidgetCache(emptyList(), null)
            return runCatching {
                val root = JSONObject(json)
                val items = root.optJSONArray("items") ?: JSONArray()
                WidgetCache(
                    items = (0 until items.length()).map { items.getJSONObject(it).toWidgetItem() },
                    generatedAt = parseInstant(root.str("generated_at")),
                )
            }.getOrDefault(WidgetCache(emptyList(), null))
        }

        fun logError(where: String, throwable: Throwable) {
            Log.w(TAG, where + " failed: " + throwable.message)
        }
    }
}

// ----------------------------------------------------------- device identity

/** What an authenticated request should do after a failure. */
internal enum class DeviceRecovery { Fail, ReRegister, Expired }

/**
 * Decide how an authenticated request recovers from a failed attempt.
 *
 * The API answers 401 when it has forgotten this device, and one re-registration
 * fixes that. A 401 that survives a fresh token means the server is refusing the
 * new identity too (or the token store is broken), so [Expired] replaces the raw
 * detail with wording that points at a retry instead of looping.
 *
 * Public endpoints are exempt: there is no token to doubt, so a 401 there must
 * never wipe the identity. [attempt] counts the retries already made by this call.
 */
internal fun deviceRecovery(status: Int, attempt: Int, auth: Boolean): DeviceRecovery = when {
    !auth || status != 401 -> DeviceRecovery.Fail
    attempt == 0 -> DeviceRecovery.ReRegister
    else -> DeviceRecovery.Expired
}

// -------------------------------------------------------------------- errors

/**
 * Human-readable message from an unsuccessful response body.
 *
 * FastAPI reports validation failures with `detail` as an *array* of error
 * objects. Reading that with optString() returned the array's toString(), so a
 * 422 from /v1/basket/compare showed ~1000 characters of echoed request JSON
 * instead of the sentence that explains the problem; the per-error `msg` fields
 * are that sentence. A plain string detail still wins (the 404 stale-slug recovery
 * reads it), and anything else falls back to the raw body as before.
 */
internal fun errorMessage(body: String, fallback: String = ""): String {
    val detail = runCatching { JSONObject(body).opt("detail") }.getOrNull()
    val message = when (detail) {
        is JSONArray -> (0 until detail.length())
            .mapNotNull { (detail.opt(it) as? JSONObject)?.optString("msg")?.trim()?.takeIf(String::isNotBlank) }
            .distinct()
            .joinToString("; ")
        is String -> detail.trim()
        else -> ""
    }
    return message.takeIf { it.isNotBlank() && it != "null" }
        ?: body.take(180).ifBlank { fallback }
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

// ------------------------------------------------------- widget batching

/**
 * Slugs per /v1/widget request.
 *
 * The endpoint parses `products` as a comma-separated list and keeps `slugs[:8]`
 * (public.py), answering 200 with no hint that anything was cut, so the only fix is
 * to ask in batches the server cannot truncate.
 */
internal const val WIDGET_BATCH = 8

/**
 * Split [slugs] into requests /v1/widget will answer in full.
 *
 * The list is de-duplicated first: a repeated slug would waste one of the eight
 * slots, and the server answers one row per requested product, so it would also
 * show the drink twice. An empty watchlist still needs one request — that is how the
 * "most widely stocked" default list is fetched.
 */
internal fun widgetBatches(slugs: List<String>): List<List<String>> {
    val wanted = slugs.distinct()
    if (wanted.isEmpty()) return listOf(emptyList())
    return wanted.chunked(WIDGET_BATCH)
}

/**
 * Fold the batched answers into one payload for the widget's offline copy.
 *
 * Rows keep the batch order, which is the watchlist order, and a row whose slug has
 * already appeared is dropped: the only way to repeat one is a suggestion drawn from
 * the whole catalogue, which can name a drink tracked in an earlier batch. Keeping
 * the first copy means the tracked row survives (unmarked as a suggestion), and it
 * keeps the slug unique — HomeScreen keys its list by it, and LazyColumn throws on a
 * duplicate key.
 *
 * `generated_at` becomes the *oldest* batch: the widget's age note is measured from
 * it, so the newest batch's timestamp would understate how old its stalest row is.
 */
internal fun mergedWidgetPayload(bodies: List<JSONObject>): JSONObject {
    // One answer is already the payload; merging it would only round-trip its
    // generated_at through Instant.toString().
    if (bodies.size <= 1) return bodies.firstOrNull() ?: JSONObject()

    val items = JSONArray()
    val seen = mutableSetOf<String>()
    bodies.forEach { body ->
        val rows = body.optJSONArray("items") ?: JSONArray()
        for (index in 0 until rows.length()) {
            val row = rows.optJSONObject(index) ?: continue
            if (seen.add(row.optString("slug"))) items.put(row)
        }
    }
    val oldest = bodies.mapNotNull { parseInstant(it.str("generated_at")) }.minOrNull()
    return JSONObject()
        .put("generated_at", oldest?.let { Instant.ofEpochMilli(it).toString() } ?: JSONObject.NULL)
        .put("radius_km", bodies.first().optDouble("radius_km", 0.0))
        .put("items", items)
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
        observationId = if (isNull("observation_id")) null else optInt("observation_id"),
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

/** Every row of a /v1/widget payload, in the order the server ranked them. */
internal fun JSONObject.toWidgetItems(): List<WidgetItem> {
    val items = optJSONArray("items") ?: JSONArray()
    return (0 until items.length()).map { items.getJSONObject(it).toWidgetItem() }
}

/** An ISO-8601 timestamp from the API as epoch millis, or null when unreadable. */
internal fun parseInstant(value: String?): Long? {
    if (value.isNullOrBlank()) return null
    return runCatching { Instant.parse(value).toEpochMilli() }
        // A tz-aware server may serialise "+10:00" instead of "Z".
        .recoverCatching { OffsetDateTime.parse(value).toInstant().toEpochMilli() }
        .getOrNull()
}

/**
 * The freshness note in the widget header, or null when nothing is cached.
 *
 * A payload's `age_hours` are frozen when the server builds it, so a copy served
 * from the offline cache would keep claiming "2 h ago" for as long as the network
 * is down. Adding the time elapsed since `generated_at` gives the real age of the
 * oldest price on the widget — the same figure the in-app list shows per row.
 */
internal fun cachedAgeLabel(generatedAt: Long?, oldestAgeHours: Double, nowMillis: Long): String? {
    generatedAt ?: return null
    val since = (nowMillis - generatedAt).coerceAtLeast(0L) / 3_600_000.0
    return Price.age(since + oldestAgeHours.coerceAtLeast(0.0))
}

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
                driveDistanceKm = if (store.isNull("drive_distance_km")) null else store.optDouble("drive_distance_km"),
                totalCents = store.getInt("total_cents"),
                fuelCostCents = if (store.isNull("fuel_cost_cents")) null else store.optInt("fuel_cost_cents"),
                totalWithFuelCents = if (store.isNull("total_with_fuel_cents")) null else store.optInt("total_with_fuel_cents"),
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
        multiStoreItemsCents = if (isNull("multi_store_items_cents")) null else optInt("multi_store_items_cents"),
        multiStoreFuelCostCents = if (isNull("multi_store_fuel_cost_cents")) null else optInt("multi_store_fuel_cost_cents"),
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

internal fun JSONObject.toPriceAlert(): PriceAlert = PriceAlert(
    id = getInt("id"),
    productId = if (isNull("product_id")) null else getInt("product_id"),
    familySlug = str("family_slug"),
    chainCode = str("chain_code"),
    storeId = if (isNull("store_id")) null else getInt("store_id"),
    // Both triggers are nullable server-side, so an old alert can carry neither.
    maxPriceCents = if (isNull("max_price_cents")) null else optInt("max_price_cents"),
    minDropPercent = if (isNull("min_drop_percent")) null else optInt("min_drop_percent"),
    lat = if (isNull("lat")) null else optDouble("lat"),
    lon = if (isNull("lon")) null else optDouble("lon"),
    radiusKm = if (isNull("radius_km")) null else optDouble("radius_km"),
    isActive = optBoolean("is_active", true),
    createdAt = optString("created_at"),
)

/**
 * Body fields for POST /v1/alerts.
 *
 * Split out from the JSONObject so the shape can be unit tested. Two rules matter:
 * the server wants lat, lon and radius_km together or not at all, and it rejects a
 * request with neither max_price_cents nor min_drop_percent. Sending no location at
 * all is what made alerts fire from the far side of the city, so the caller always
 * passes the device's stored position.
 */
internal fun AlertRequest.toFields(): Map<String, Any> = buildMap {
    put("product_slug", productSlug)
    maxPriceCents?.let { put("max_price_cents", it) }
    minDropPercent?.let { put("min_drop_percent", it) }
    if (lat != null && lon != null && radiusKm != null) {
        put("lat", lat)
        put("lon", lon)
        put("radius_km", radiusKm)
    }
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
