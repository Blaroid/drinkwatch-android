package com.drinkwatch.app.data

import okhttp3.HttpUrl
import okhttp3.Request
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/**
 * The cache behaviour the API relies on: /v1/widget is served with max-age plus
 * a weak ETag, so OkHttp must reuse the body inside the window, revalidate with
 * If-None-Match afterwards, and keep the last good copy when the network dies.
 */
class HttpCacheTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var transport: HttpTransport

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        // One transport per test, like the one Api per process: a response is
        // always read back through the same cache instance that stored it.
        transport = HttpTransport(tmp.newFolder("cache"))
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    private fun fetch(url: HttpUrl): String {
        val response = transport.call(Request.Builder().url(url).build())
        return response.use { it.body?.string() ?: "" }
    }

    private fun payload(tag: String) = """{"generated_at":"$tag","items":[]}"""

    @Test
    fun `fresh response is served from disk without a second request`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Cache-Control", "max-age=300")
                .setHeader("ETag", "W/\"v1\"")
                .setBody(payload("first")),
        )
        val url = server.url("/v1/widget")

        assertEquals(payload("first"), fetch(url))
        assertEquals(payload("first"), fetch(url))
        assertEquals(1, server.requestCount)
        assertNull(server.takeRequest().getHeader("If-None-Match"))
    }

    @Test
    fun `stale response is revalidated with the stored etag and reused on 304`() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                when {
                    // max-age=0 keeps the second call from waiting out a real
                    // five-minute freshness window.
                    request.getHeader("If-None-Match") == null -> MockResponse()
                        .setResponseCode(200)
                        .setHeader("Cache-Control", "max-age=0")
                        .setHeader("ETag", "W/\"v1\"")
                        .setBody(payload("first"))

                    request.getHeader("If-None-Match") == "W/\"v1\"" -> MockResponse()
                        .setResponseCode(304)
                        .setHeader("ETag", "W/\"v1\"")

                    else -> MockResponse().setResponseCode(500)
                }
        }
        val url = server.url("/v1/widget")

        assertEquals(payload("first"), fetch(url))
        assertEquals(payload("first"), fetch(url))

        assertNull(server.takeRequest().getHeader("If-None-Match"))
        assertEquals("W/\"v1\"", server.takeRequest().getHeader("If-None-Match"))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `network failure falls back to the stale copy`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Cache-Control", "max-age=0")
                .setHeader("ETag", "W/\"v1\"")
                .setBody(payload("first")),
        )
        val url = server.url("/v1/widget")
        assertEquals(payload("first"), fetch(url))

        // Tunnel outage: nothing answers, but the widget keeps its prices.
        server.shutdown()
        assertEquals(payload("first"), fetch(url))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `uncached network failure is still an error`() {
        val url = server.url("/v1/widget")
        server.shutdown()

        val failure = runCatching { fetch(url) }
        assertNotNull(failure.exceptionOrNull())
        assertTrue(failure.exceptionOrNull() is IOException)
    }
}
