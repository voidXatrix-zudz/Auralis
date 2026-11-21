package com.auralis.app.data.lyrics

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Safety tests for the shared provider HTTP layer: a bad request must resolve to null, never throw,
 * so a provider can never crash the lyrics screen or escape the repository's error handling.
 */
class ProviderHttpTest {

    private val http = ProviderHttp(OkHttpClient())

    @Test
    fun `a malformed url resolves to null instead of throwing`() = runBlocking {
        assertNull(http.getText("not a url"))
        assertNull(http.getJson("not a url"))
        assertNull(http.getText(""))
    }

    @Test
    fun `a malformed post url resolves to null instead of throwing`() = runBlocking {
        assertNull(http.postJson("not a url", "{}"))
    }
}
