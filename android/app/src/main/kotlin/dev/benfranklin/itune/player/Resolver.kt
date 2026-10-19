package dev.benfranklin.itune.player

import android.net.Uri
import dev.benfranklin.itune.App
import dev.benfranklin.itune.core.StreamInfo
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.stream.StreamInfo as NpStreamInfo
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Resolved playback target for one track. */
data class Resolved(val uri: Uri, val userAgent: String?, val client: String, val loudnessDb: Float?)

/**
 * Turns a track id into something ExoPlayer can open:
 *  downloaded file > local file > Rust core stream (JS-less InnerTube client chain) > NewPipeExtractor fallback.
 */
object Resolver {
    val clientFor = ConcurrentHashMap<String, String>()
    @Volatile var lastError: String = ""

    /** A replacement chosen after a playback failure (used once by the next open). */
    val overrides = ConcurrentHashMap<String, Resolved>()
    val loudness = ConcurrentHashMap<String, Float>()

    /** Blocks the calling (loader) thread only. Never call on the main thread. */
    private fun <T> blocking(block: suspend () -> T): T {
        check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) { "Resolver called on the main thread" }
        return runBlocking(kotlinx.coroutines.Dispatchers.IO) { kotlinx.coroutines.withTimeout(25_000) { block() } }
    }

    fun resolve(id: String): Resolved {
        overrides[id]?.let { return it }
        if (id.startsWith("local:")) return Resolved(Uri.parse(id.removePrefix("local:")), null, "local", null)
        runCatching { App.core.offlinePath(id) }.getOrNull()?.let { return Resolved(Uri.fromFile(java.io.File(it)), null, "offline", null) }
        val s: StreamInfo? = runCatching { blocking { App.core.stream(id) } }.onFailure { lastError = it.message ?: "$it" }.getOrNull()
        if (s != null) {
            clientFor[id] = s.client
            s.loudnessDb?.let { loudness[id] = it }
            return Resolved(Uri.parse(s.url), s.userAgent, s.client, s.loudnessDb)
        }
        val np = runCatching { newPipe(id) }.onFailure { lastError += "; newpipe: ${it.message}" }.getOrNull()
        if (np != null) {
            clientFor[id] = "newpipe"
            return np
        }
        throw java.io.IOException("No stream for $id: $lastError")
    }

    /** Called when playback of a resolved URL failed (403 / throttled). */
    fun retry(id: String, error: String): Resolved {
        val failed = clientFor[id] ?: "visionos"
        if (failed != "newpipe") {
            val s = runCatching { blocking { App.core.streamRetry(id, failed, error) } }.getOrNull()
            if (s != null) {
                clientFor[id] = s.client
                return Resolved(Uri.parse(s.url), s.userAgent, s.client, s.loudnessDb)
            }
        }
        return newPipe(id).also { clientFor[id] = "newpipe" }
    }

    // ---------------------------------------------------------------- NewPipeExtractor (second opinion)
    private val http = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build()
    @Volatile private var npInit = false

    private class NpDownloader(private val c: OkHttpClient) : Downloader() {
        override fun execute(request: Request): Response {
            val b = okhttp3.Request.Builder().url(request.url())
            request.headers().forEach { (k, vs) -> vs.forEach { v -> b.addHeader(k, v) } }
            if (request.headers()["User-Agent"].isNullOrEmpty()) {
                b.header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0")
            }
            val data = request.dataToSend()
            b.method(request.httpMethod(), data?.toRequestBody())
            c.newCall(b.build()).execute().use { r ->
                return Response(r.code, r.message, r.headers.toMultimap(), r.body?.string(), r.request.url.toString())
            }
        }
    }

    private fun newPipe(id: String): Resolved {
        if (!npInit) {
            synchronized(this) {
                if (!npInit) {
                    NewPipe.init(NpDownloader(http))
                    npInit = true
                }
            }
        }
        val info = NpStreamInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=$id")
        val best = info.audioStreams.filter { it.isUrl }.maxByOrNull { it.averageBitrate }
            ?: throw java.io.IOException("newpipe: no audio")
        return Resolved(Uri.parse(best.content), null, "newpipe", null)
    }
}
