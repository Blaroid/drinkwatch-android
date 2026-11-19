package com.drinkwatch.app.data

import okhttp3.Cache
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Disk budget for the response cache; the widget payload is a few kilobytes. */
internal const val HTTP_CACHE_BYTES = 8L * 1024 * 1024

/**
 * OkHttp with a disk cache, plus a stale-copy fallback.
 *
 * The API sends /v1/widget with `Cache-Control: max-age=300` and a weak ETag, so
 * the JSON is kept on disk for five minutes and then revalidated instead of
 * downloaded again (the server answers 304 with headers only).
 *
 * OkHttp implements no `stale-if-error`, so a call that dies on the network is
 * retried once against the cache alone: an outage of the Cloudflare tunnel then
 * shows the last known prices rather than an error. The retry is deliberately
 * kept off the normal path — asking for `max-stale` up front would make OkHttp
 * skip revalidation altogether inside the stale window and never send the ETag.
 */
internal class HttpTransport(cacheDir: File?) {

    private val cache: Cache? = cacheDir?.let { Cache(File(it, "http"), HTTP_CACHE_BYTES) }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        .callTimeout(70, TimeUnit.SECONDS)
        .apply { cache?.let { cache(it) } }
        .build()

    val http: OkHttpClient get() = client

    /** Execute [request], falling back to the last cached copy if the network fails. */
    fun call(request: Request): Response = try {
        client.newCall(request).execute()
    } catch (offline: IOException) {
        stale(request) ?: throw offline
    }

    /**
     * The cached response for [request] however old it is, or null when the
     * cache cannot satisfy the call (then the real network error wins).
     */
    private fun stale(request: Request): Response? {
        // A POST cannot be served from cache, and without a cache there is
        // nothing to fall back to; skip the extra call rather than wait twice.
        if (cache == null || request.method != "GET") return null
        val stale = request.newBuilder()
            .cacheControl(CacheControl.Builder().onlyIfCached().maxStale(STALE_DAYS, TimeUnit.DAYS).build())
            .build()
        val response = try {
            client.newCall(stale).execute()
        } catch (_: IOException) {
            return null
        }
        // `only-if-cached` with nothing usable answers a synthetic 504.
        if (!response.isSuccessful) {
            response.close()
            return null
        }
        return response
    }

    private companion object {
        /** Long enough to cover a day-long outage; the UI still shows the timestamp. */
        const val STALE_DAYS = 30
    }
}
