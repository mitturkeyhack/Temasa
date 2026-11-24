package com.erkam.temasa.yt

import okhttp3.ConnectionPool
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import java.util.concurrent.TimeUnit

object Net {
    const val BROWSER_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"

    /** googlevideo video ve ses bağlantıları, onları alan istemcinin kimliğiyle istenmeli; yoksa 403 döner. */
    private val streamHeaders = Interceptor { chain ->
        val req = chain.request()
        val url = req.url.toString()
        if (!req.url.host.endsWith("googlevideo.com")) return@Interceptor chain.proceed(req)
        val b = req.newBuilder()
        when {
            YoutubeParsingHelper.isVisionOsStreamingUrl(url) ->
                b.header("User-Agent", YoutubeParsingHelper.getVisionOsUserAgent(null))
            YoutubeParsingHelper.isWebStreamingUrl(url) -> b
                .header("User-Agent", BROWSER_UA)
                .header("Origin", "https://www.youtube.com")
                .header("Referer", "https://www.youtube.com/")
            else -> b.header("User-Agent", BROWSER_UA)
        }
        chain.proceed(b.build())
    }

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectionPool(ConnectionPool(10, 5, TimeUnit.MINUTES))
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .addInterceptor(streamHeaders)
        .build()
}

/** NewPipe Extractor'un internete çıkışı. */
class NpDownloader : Downloader() {
    override fun execute(request: Request): Response {
        val b = okhttp3.Request.Builder().url(request.url())
        var hasUa = false
        request.headers().forEach { (k, values) ->
            if (k.equals("User-Agent", true)) hasUa = true
            b.removeHeader(k)
            values.forEach { b.addHeader(k, it) }
        }
        if (!hasUa) b.header("User-Agent", Net.BROWSER_UA)
        val data = request.dataToSend()
        b.method(request.httpMethod(), data?.toRequestBody())
        Net.client.newCall(b.build()).execute().use { r ->
            return Response(r.code, r.message, r.headers.toMultimap(), r.body.string(), r.request.url.toString())
        }
    }
}
