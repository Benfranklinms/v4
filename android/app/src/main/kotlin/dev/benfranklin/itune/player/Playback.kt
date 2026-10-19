package dev.benfranklin.itune.player

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import dev.benfranklin.itune.App
import dev.benfranklin.itune.core.QueueState
import dev.benfranklin.itune.core.Track
import dev.benfranklin.itune.widget.NowPlayingWidget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.TimeZone
import kotlin.math.pow

/**
 * In-process playback hub. The ExoPlayer lives in [PlaybackService] (foreground mediaPlayback service, so it keeps
 * playing with the screen off and the app swiped away); the UI drives it through this object on the main thread.
 * The Rust core owns the queue logic (shuffle order, repeat, autoplay tail, reranking); ExoPlayer mirrors it.
 */
object Playback {
    var player: ExoPlayer? = null
        private set
    private val main = Handler(Looper.getMainLooper())
    private val tracks = HashMap<String, Track>()
    private var controller: MediaController? = null
    private val pending = ArrayList<() -> Unit>()

    private val _current = MutableStateFlow<Track?>(null)
    val current: StateFlow<Track?> = _current
    private val _playing = MutableStateFlow(false)
    val playing: StateFlow<Boolean> = _playing
    private val _buffering = MutableStateFlow(false)
    val buffering: StateFlow<Boolean> = _buffering
    private val _position = MutableStateFlow(0L)
    val position: StateFlow<Long> = _position
    private val _duration = MutableStateFlow(0L)
    val duration: StateFlow<Long> = _duration
    private val _queue = MutableStateFlow<QueueState?>(null)
    val queue: StateFlow<QueueState?> = _queue
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error
    private val _speed = MutableStateFlow(1f)
    val speed: StateFlow<Float> = _speed

    // stats
    private var playStartedAt = 0L
    private var listenedMs = 0L
    private var lastTick = 0L
    private var retried = HashSet<String>()
    private var ticker: Job? = null

    /** Ensure the session service is running (binds a MediaController), then run [block]. */
    fun ensure(ctx: Context, block: () -> Unit) {
        if (player != null) {
            block()
            return
        }
        pending += block
        if (controller != null) return
        val token = SessionToken(ctx, ComponentName(ctx, PlaybackService::class.java))
        val f = MediaController.Builder(ctx, token).buildAsync()
        f.addListener({
            controller = runCatching { f.get() }.getOrNull()
            flush()
        }, { main.post(it) })
    }

    internal fun attach(p: ExoPlayer) {
        player = p
        p.addListener(listener)
        startTicker()
        flush()
    }

    internal fun detach() {
        finishPlay(false)
        player?.removeListener(listener)
        player = null
        ticker?.cancel()
        controller?.release()
        controller = null
    }

    private fun flush() {
        if (player == null) return
        val l = pending.toList()
        pending.clear()
        l.forEach { it() }
    }

    private fun item(t: Track): MediaItem {
        tracks[t.id] = t
        val b = MediaItem.Builder()
            .setMediaId(t.id)
            .setUri(Uri.parse("itune:${t.id}"))
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(t.title)
                    .setArtist(t.artists)
                    .setAlbumTitle(t.album)
                    .setArtworkUri(t.thumb.takeIf { it.isNotEmpty() }?.let(Uri::parse))
                    .setIsPlayable(true)
                    .setIsBrowsable(false)
                    .build(),
            )
        if (!t.id.startsWith("local:")) b.setCustomCacheKey(t.id)
        return b.build()
    }

    fun trackFor(id: String?): Track? = id?.let { tracks[it] }

    // ------------------------------------------------------------------ commands
    fun play(ctx: Context, list: List<Track>, index: Int, source: String) = ensure(ctx) {
        val p = player ?: return@ensure
        finishPlay(false)
        App.core.queueSet(list, index, source)
        val q = App.core.queueState()
        p.setMediaItems(q.tracks.map(::item), q.index.coerceAtLeast(0), 0L)
        p.repeatMode = repeatOf(q.repeat.toInt())
        p.prepare()
        p.play()
        publishQueue()
    }

    fun playShuffled(ctx: Context, list: List<Track>, source: String) = ensure(ctx) {
        App.core.setShuffle(true)
        play(ctx, list, (list.indices).random(), source)
    }

    fun toggle() {
        val p = player ?: return
        if (p.isPlaying) p.pause() else {
            if (p.playbackState == Player.STATE_IDLE) p.prepare()
            p.play()
        }
    }

    fun next() {
        val p = player ?: return
        if (p.hasNextMediaItem()) p.seekToNextMediaItem() else App.scope.launch { extendAutoplay(force = true); withContext(Dispatchers.Main) { player?.seekToNextMediaItem() } }
    }

    fun prev() {
        val p = player ?: return
        if (p.currentPosition > 4000 || !p.hasPreviousMediaItem()) p.seekTo(0) else p.seekToPreviousMediaItem()
    }

    fun seek(ms: Long) {
        player?.seekTo(ms)
        _position.value = ms
    }

    fun jump(index: Int) {
        player?.seekTo(index, 0)
        player?.play()
    }

    fun setShuffle(on: Boolean) {
        val p = player ?: return
        App.core.setShuffle(on)
        rebuildTail()
        publishQueue()
    }

    fun cycleRepeat() {
        val r = App.core.cycleRepeat().toInt()
        player?.repeatMode = repeatOf(r)
        publishQueue()
    }

    fun setSpeed(s: Float) {
        player?.setPlaybackSpeed(s)
        _speed.value = s
    }

    fun playNext(ctx: Context, t: Track) = ensure(ctx) {
        val p = player ?: return@ensure
        if (p.mediaItemCount == 0) return@ensure play(ctx, listOf(t), 0, "Queue")
        App.core.queuePlayNext(t)
        p.addMediaItem(p.currentMediaItemIndex + 1, item(t))
        publishQueue()
    }

    fun addToQueue(ctx: Context, t: Track) = ensure(ctx) {
        val p = player ?: return@ensure
        if (p.mediaItemCount == 0) return@ensure play(ctx, listOf(t), 0, "Queue")
        App.core.queueAdd(t)
        val q = App.core.queueState()
        val pos = q.tracks.indexOfFirst { it.id == t.id }.takeIf { it >= 0 } ?: p.mediaItemCount
        p.addMediaItem(pos.coerceAtMost(p.mediaItemCount), item(t))
        publishQueue()
    }

    fun remove(index: Int) {
        val p = player ?: return
        if (index == p.currentMediaItemIndex) return
        App.core.queueRemove(index)
        p.removeMediaItem(index)
        publishQueue()
    }

    fun move(from: Int, to: Int) {
        val p = player ?: return
        App.core.queueMove(from, to)
        p.moveMediaItem(from, to)
        publishQueue()
    }

    /** Replace everything after the current item with the core's order (after shuffle toggles). */
    private fun rebuildTail() {
        val p = player ?: return
        val q = App.core.queueState()
        val cur = p.currentMediaItemIndex
        if (cur + 1 < p.mediaItemCount) p.removeMediaItems(cur + 1, p.mediaItemCount)
        val tail = q.tracks.drop(q.index + 1)
        if (tail.isNotEmpty()) p.addMediaItems(tail.map(::item))
    }

    private fun publishQueue() {
        _queue.value = App.core.queueState()
    }

    private fun repeatOf(r: Int) = when (r) {
        1 -> Player.REPEAT_MODE_ALL
        2 -> Player.REPEAT_MODE_ONE
        else -> Player.REPEAT_MODE_OFF
    }

    /** Infinite autoplay: extend with reranked radio when the queue is about to end. */
    private suspend fun extendAutoplay(force: Boolean = false) {
        val n = runCatching { App.core.ensureAutoplay() }.getOrDefault(0u)
        if (n.toInt() == 0 && !force) return
        withContext(Dispatchers.Main) {
            val p = player ?: return@withContext
            val q = App.core.queueState()
            val have = p.mediaItemCount
            if (q.tracks.size > have) p.addMediaItems(q.tracks.drop(have).map(::item))
            publishQueue()
        }
    }

    // ------------------------------------------------------------------ listener
    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val skipped = reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK
            finishPlay(skipped)
            val p = player ?: return
            App.core.queueJump(p.currentMediaItemIndex)
            val t = trackFor(mediaItem?.mediaId)
            _current.value = t
            _duration.value = (t?.durationS?.toLong() ?: 0L) * 1000
            playStartedAt = System.currentTimeMillis() / 1000
            listenedMs = 0
            publishQueue()
            applyLoudness(mediaItem?.mediaId)
            NowPlayingWidget.refresh(App.instance)
            App.scope.launch {
                if (p.mediaItemCount - p.currentMediaItemIndex <= 3) extendAutoplay()
                prefetchUpcoming()
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _playing.value = isPlaying
            lastTick = System.currentTimeMillis()
            NowPlayingWidget.refresh(App.instance)
        }

        override fun onPlaybackStateChanged(state: Int) {
            _buffering.value = state == Player.STATE_BUFFERING
            if (state == Player.STATE_READY) {
                _error.value = null
                player?.duration?.takeIf { it > 0 }?.let { _duration.value = it }
            }
            if (state == Player.STATE_ENDED) {
                finishPlay(false)
                App.scope.launch {
                    extendAutoplay(force = true)
                    withContext(Dispatchers.Main) {
                        val p = player ?: return@withContext
                        if (p.hasNextMediaItem()) {
                            p.seekToNextMediaItem()
                            p.play()
                        }
                    }
                }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            val p = player ?: return
            val id = p.currentMediaItem?.mediaId ?: return
            val pos = p.currentPosition
            val idx = p.currentMediaItemIndex
            val http = (error.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode
            val msg = http?.let { "HTTP $it" } ?: (error.errorCodeName + ": " + (error.cause?.message ?: error.message))
            // 403 / throttling / dead URL -> next client in the chain (once per track), then NewPipe, then skip.
            if (retried.add(id)) {
                App.scope.launch {
                    val r = runCatching { Resolver.retry(id, msg) }.getOrNull()
                    withContext(Dispatchers.Main) {
                        if (r != null) Resolver.overrides[id] = r
                        player?.let {
                            it.prepare()
                            it.seekTo(idx, pos)
                            it.play()
                        }
                        delay(15_000)
                        Resolver.overrides.remove(id)
                    }
                }
            } else {
                _error.value = "Couldn't play this track ($msg). Skipping."
                if (p.hasNextMediaItem()) {
                    p.seekToNextMediaItem()
                    p.prepare()
                    p.play()
                }
            }
        }
    }

    private fun applyLoudness(id: String?) {
        val p = player ?: return
        if (!App.prefs.normalize.value) {
            p.volume = 1f
            return
        }
        val db = id?.let { Resolver.loudness[it] } ?: 0f
        // YouTube reports loudness relative to its -14 LUFS target; attenuate louder tracks only.
        p.volume = if (db > 0f) 10f.pow(-db / 20f).coerceIn(0.2f, 1f) else 1f
    }

    private suspend fun prefetchUpcoming() {
        val ids = withContext(Dispatchers.Main) {
            val p = player ?: return@withContext emptyList<String>()
            (1..3).mapNotNull { k -> p.currentMediaItemIndex.plus(k).takeIf { it < p.mediaItemCount }?.let { p.getMediaItemAt(it).mediaId } }
        }
        withContext(Dispatchers.IO) {
            ids.forEach { id -> runCatching { DataSources.prefetch(App.instance, id) } }
        }
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = App.scope.launch(Dispatchers.Main) {
            while (true) {
                val p = player ?: break
                _position.value = p.currentPosition
                if (p.isPlaying) {
                    val now = System.currentTimeMillis()
                    if (lastTick > 0) listenedMs += (now - lastTick).coerceAtMost(2000)
                    lastTick = now
                    if (_current.value == null) _current.value = trackFor(p.currentMediaItem?.mediaId)
                } else lastTick = 0
                delay(250)
            }
        }
    }

    private fun finishPlay(skipped: Boolean) {
        val t = _current.value ?: return
        val ms = listenedMs
        listenedMs = 0
        if (ms < 1000) return
        val dur = (t.durationS.toLong() * 1000).coerceAtLeast(1)
        val wasSkip = skipped && ms < 30_000 && ms < dur / 2
        val tz = TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60_000
        App.scope.launch { runCatching { App.core.recordPlay(t, playStartedAt, ms.toULong(), wasSkip, tz, "android") } }
    }

    fun positionFraction(): Float {
        val d = _duration.value
        return if (d > 0) (_position.value.toFloat() / d).coerceIn(0f, 1f) else 0f
    }

    fun restoreFromSession(p: ExoPlayer) {
        p.currentMediaItem?.let { _current.value = trackFor(it.mediaId) }
    }

    @Suppress("unused")
    private const val UNSET = C.TIME_UNSET
}
