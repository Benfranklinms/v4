package dev.benfranklin.itune.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheEvictor
import androidx.media3.datasource.cache.CacheSpan
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import dev.benfranklin.itune.App
import okhttp3.OkHttpClient
import java.io.File
import java.util.TreeSet
import java.util.concurrent.TimeUnit

/** Least-recently-used evictor whose cap can change at runtime (Settings slider), trimming immediately. */
class MutableLruEvictor(@Volatile var maxBytes: Long) : CacheEvictor {
    private val spans = TreeSet<CacheSpan> { a, b ->
        val d = a.lastTouchTimestamp - b.lastTouchTimestamp
        if (d == 0L) a.compareTo(b) else if (d < 0) -1 else 1
    }
    private var size = 0L
    override fun requiresCacheSpanTouches() = true
    override fun onCacheInitialized() {}
    override fun onStartFile(cache: Cache, key: String, position: Long, length: Long) {
        if (length != C.LENGTH_UNSET.toLong()) evict(cache, length)
    }
    override fun onSpanAdded(cache: Cache, span: CacheSpan) {
        synchronized(this) { spans.add(span); size += span.length }
        evict(cache, 0)
    }
    override fun onSpanRemoved(cache: Cache, span: CacheSpan) {
        synchronized(this) { if (spans.remove(span)) size -= span.length }
    }
    override fun onSpanTouched(cache: Cache, oldSpan: CacheSpan, newSpan: CacheSpan) {
        onSpanRemoved(cache, oldSpan); onSpanAdded(cache, newSpan)
    }
    fun evict(cache: Cache, required: Long) {
        while (true) {
            val victim = synchronized(this) { if (size + required > maxBytes && spans.isNotEmpty()) spans.first() else null } ?: return
            runCatching { cache.removeSpan(victim) }.onFailure { synchronized(this) { spans.remove(victim); size -= victim.length } }
        }
    }
}

/** Opens http(s) through the LRU cache, everything else (files, content://) directly. */
class RoutingDataSource(private val net: DataSource, private val local: DataSource) : DataSource {
    private var cur: DataSource? = null
    override fun addTransferListener(transferListener: TransferListener) {
        net.addTransferListener(transferListener)
        local.addTransferListener(transferListener)
    }
    override fun open(dataSpec: DataSpec): Long {
        val s = if (dataSpec.uri.scheme?.startsWith("http") == true) net else local
        cur = s
        return s.open(dataSpec)
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = cur!!.read(buffer, offset, length)
    override fun getUri(): Uri? = cur?.uri
    override fun getResponseHeaders(): Map<String, List<String>> = cur?.responseHeaders ?: emptyMap()
    override fun close() {
        try { cur?.close() } finally { cur = null }
    }
}

object DataSources {
    private var cache: SimpleCache? = null
    private var evictor: MutableLruEvictor? = null
    private fun capBytes() = App.prefs.cacheMb.value.toLong() * 1024 * 1024
    val cacheOn get() = App.prefs.cacheMb.value > 0
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()

    @Synchronized
    fun cache(ctx: Context): SimpleCache = cache ?: SimpleCache(
        File(ctx.cacheDir, "media"),
        MutableLruEvictor(capBytes()).also { evictor = it },
        StandaloneDatabaseProvider(ctx),
    ).also { cache = it }

    private fun okhttp() = OkHttpDataSource.Factory(http)

    fun cacheFactory(ctx: Context): CacheDataSource.Factory = CacheDataSource.Factory()
        .setCache(cache(ctx))
        .setUpstreamDataSourceFactory(okhttp())
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    /** itune:<id> -> resolved stream / file, with the stream's User-Agent. */
    fun playerFactory(ctx: Context): DataSource.Factory {
        val routing = DataSource.Factory {
            val net = if (cacheOn) cacheFactory(ctx).createDataSource() else okhttp().createDataSource()
            RoutingDataSource(net, DefaultDataSource.Factory(ctx, okhttp()).createDataSource())
        }
        return ResolvingDataSource.Factory(routing) { spec ->
            if (spec.uri.scheme == "itune") {
                val id = spec.uri.schemeSpecificPart
                val r = Resolver.resolve(id)
                var s = spec.withUri(r.uri)
                if (r.userAgent != null) s = s.withAdditionalHeaders(mapOf("User-Agent" to r.userAgent))
                s
            } else spec
        }
    }

    /** Pre-cache a whole track (next 2–3 in the queue) so playback survives short outages. */
    fun prefetch(ctx: Context, id: String) {
        // Downloads play from their file and are never cached twice; prefetch counts inside the cap.
        if (!cacheOn || id.startsWith("local:") || App.core.offlinePath(id) != null) return
        val r = Resolver.resolve(id)
        if (r.uri.scheme?.startsWith("http") != true) return
        val spec = DataSpec.Builder()
            .setUri(r.uri)
            .setKey(id)
            .setHttpRequestHeaders(r.userAgent?.let { mapOf("User-Agent" to it) } ?: emptyMap())
            .setLength(C.LENGTH_UNSET.toLong())
            .build()
        CacheWriter(cacheFactory(ctx).createDataSource(), spec, null, null).cache()
    }

    /** Applies the Settings cap now (and on app start): trims the LRU tail to fit. */
    fun applyCap(ctx: Context) {
        val c = cache(ctx)
        evictor?.let { it.maxBytes = capBytes(); it.evict(c, 0) }
        if (!cacheOn) clear(ctx)
    }

    fun cachedBytes(ctx: Context): Long = runCatching { cache(ctx).cacheSpace }.getOrDefault(0L)
    fun clear(ctx: Context) {
        val c = cache(ctx)
        c.keys.toList().forEach { runCatching { c.removeResource(it) } }
    }
}
