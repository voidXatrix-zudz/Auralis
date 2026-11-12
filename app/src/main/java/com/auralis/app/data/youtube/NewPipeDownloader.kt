package com.auralis.app.data.youtube

import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response

/**
 * NewPipeExtractor's HTTP bridge over the app's OkHttp client.
 *
 * The extractor only needs this to fetch the watch page and player response; the actual audio is
 * streamed by Media3, never downloaded by this class.
 */
class NewPipeDownloader(private val http: OkHttpClient) : Downloader() {

    override fun execute(request: Request): Response {
        val method = request.httpMethod().uppercase()
        val builder = okhttp3.Request.Builder().url(request.url())

        request.headers().forEach { (name, values) ->
            if (name.lowercase() !in IGNORED_HEADERS) {
                values.forEach { value -> builder.addHeader(name, value) }
            }
        }

        val body = request.dataToSend()?.toRequestBody(null)
        when (method) {
            "GET", "HEAD" -> builder.method(method, null)
            else -> builder.method(method, body ?: ByteArray(0).toRequestBody(null))
        }

        http.newCall(builder.build()).execute().use { response ->
            return Response(
                response.code,
                response.message,
                response.headers.toMultimap(),
                response.body?.string().orEmpty(),
                response.request.url.toString(),
            )
        }
    }

    private companion object {
        // OkHttp sets/handles these itself; passing them through makes the call throw.
        val IGNORED_HEADERS = setOf(
            "host", "connection", "content-length", "transfer-encoding",
            "upgrade", "keep-alive", "proxy-connection", "expect",
        )
    }
}
