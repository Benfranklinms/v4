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
import dev.benfranklin.itune.data.Resume
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
    private val tracks = java.util.concurrent.ConcurrentHashMap<String, Track>()
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
    /** Sleep timer: epoch ms to pause at, END_OF_TRACK, or 0 (off). */
    val sleepAt = MutableStateFlow(0L)
    const val END_OF_TRACK = -1L
    private var fadeIn = 1f
    private var lastResumeSave = 0L

    // stats
    private var playStartedAt = 0L
    private var listenedMs = 0L
    private var lastTick = 0L
    private var retried = HashSet<String>()
    private var ticker: Job? = null
    @Volatile private var connecting = false

    /**
     * Every Rust core call from playback runs on this single background thread (FIFO, so queue edits keep their
     * order); ExoPlayer changes are then posted back to the main looper in the same order. Nothing here calls
     * the core on the main thread.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val coreQ = Dispatchers.IO.limitedParallelism(1)

    private fun onCore(block: suspend () -> Unit) {
        App.scope.launch(coreQ) { runCatching { block() }.onFailure { android.util.Log.w("iTune", "queue op failed", it) } }
    }

    private suspend fun onMain(block: () -> Unit) = withContext(Dispatchers.Main) { block() }

    /** Ensure the session service is running (binds a MediaController), then run [block]. */
    fun ensure(ctx: Context, block: () -> Unit) {
        if (player != null) {
            block()
            return
        }
        pending += block
        if (controller != null || connecting) return
        connecting = true
        // Bind with the APPLICATION context: binding with an Activity leaked the ServiceConnection when the
        // Activity was destroyed, and the later release() crashed with "Service not registered" (0.1.0 crash).
        val app = ctx.applicationContext
        val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
        val f = MediaController.Builder(app, token).buildAsync()
        f.addListener({
            controller = runCatching { f.get() }.getOrNull()
            connecting = false
            flush()
        }, { main.post(it) })
    }

    internal fun attach(p: ExoPlayer) {
        player = p
        p.addListener(listener)
        restoreSaved(p)
        startTicker()
        flush()
    }

    /**
     * Relaunch: put the last session back into the player PAUSED at its position (mini player shows it).
     * The core restores it from its DB on the core thread (no network, nothing plays); the stream is only
     * resolved when the user presses Play.
     */
    private fun restoreSaved(p: ExoPlayer) {
        if (p.mediaItemCount > 0) return
        onCore {
            val saved = Session.restore(App.instance) ?: return@onCore
            if (!saved.fromCore) App.core.queueSet(saved.tracks, saved.index, saved.source)
            val q = App.core.queueState()
            val list = q.tracks.ifEmpty { saved.tracks }
            val idx = if (q.tracks.isNotEmpty()) q.index.coerceIn(0, list.lastIndex) else saved.index
            onMain {
                val pl = player ?: return@onMain
                if (pl.mediaItemCount > 0) return@onMain   // the user started something meanwhile
                pl.setMediaItems(list.map(::item), idx, saved.positionMs)
                pl.repeatMode = repeatOf(q.repeat.toInt())
                pl.playWhenReady = false
                pl.prepare()
                _current.value = list[idx]
                _position.value = saved.positionMs
                _queue.value = q
                android.util.Log.i(TAG, "restored idx=$idx/${list.size} id=${list[idx].id} pos=${saved.positionMs} title=${list[idx].title} n=${list.size}")
            }
        }
    }

    /**
     * Persist the session in the core (pause, track change, seek, queue edits, every 5 s while playing). Main thread.
     * Requests are COALESCED: a burst (repeated seeks, a track change + pause + queue publish) becomes one save
     * ~[SAVE_COALESCE_MS] later, taken from the player at that moment. The core write itself always runs on the core
     * thread, never here. Lifecycle exits use [saveSessionImmediate] / [saveSessionNow].
     */
    fun saveSession() {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { saveSession() }; return }
        if (savePending) return
        savePending = true
        main.postDelayed(saveRunnable, SAVE_COALESCE_MS)
    }

    @Volatile private var savePending = false
    private val saveRunnable = Runnable { savePending = false; saveSessionImmediate() }

    /** One save right now (snapshot on the main thread, core write on the core thread). Main thread. */
    fun saveSessionImmediate() {
        if (savePending) { main.removeCallbacks(saveRunnable); savePending = false }
        val p = player ?: return
        if (p.mediaItemCount == 0) return
        val pos = p.currentPosition
        val idx = p.currentMediaItemIndex
        val curItem = p.currentMediaItem ?: return
        val id = curItem.mediaId
        val title = curItem.mediaMetadata.title?.toString().orEmpty()
        val ids = playerIds(p)
        onCore {
            // the core saves ITS queue + index: make sure its current entry is the player's current item first
            val ci = alignCore(idx, id)
            Session.save(pos)
            val q = App.core.queueState()
            android.util.Log.i(TAG, "saved idx=$idx core=$ci/${q.tracks.size} id=$id pos=$pos title=$title")
            if (q.tracks.map { it.id } != ids) {
                android.util.Log.w(TAG, "queue mismatch: player ${ids.size} vs core ${q.tracks.size}; rebuilding the player around the current item")
                onMain { reconcile(q) }
            }
        }
    }

    /**
     * Exit paths (activity stop, task removed, service destroyed). The player is read on the main thread, the core
     * write runs on its own short-lived thread, and the main thread waits for it at most [waitMs] (a real, bounded
     * Thread.join: 0.2.2 used runBlocking(coreQ) here, which queued behind any core work and could not be
     * interrupted while an FFI call was blocked, freezing the UI on stop/return).
     */
    fun saveSessionNow(reason: String, waitMs: Long = 0L) {
        if (savePending) { main.removeCallbacks(saveRunnable); savePending = false }
        val p = player ?: return
        if (p.mediaItemCount == 0) return
        val pos = p.currentPosition
        val idx = p.currentMediaItemIndex
        val id = p.currentMediaItem?.mediaId ?: return
        val title = p.currentMediaItem?.mediaMetadata?.title?.toString().orEmpty()
        val n = p.mediaItemCount
        val t0 = android.os.SystemClock.uptimeMillis()
        val th = Thread({
            runCatching {
                val ci = alignCore(idx, id)
                Session.save(pos)
                android.util.Log.i(TAG, "saved idx=$idx core=$ci/$n id=$id pos=$pos title=$title (sync: $reason) in ${android.os.SystemClock.uptimeMillis() - t0}ms")
            }.onFailure { android.util.Log.w(TAG, "exit save failed ($reason)", it) }
        }, "itune-exit-save")
        th.start()
        if (waitMs > 0) runCatching { th.join(waitMs) }
    }

    private fun playerIds(p: ExoPlayer): List<String> = (0 until p.mediaItemCount).map { p.getMediaItemAt(it).mediaId }

    /** Index of [id] in [tracks] closest to [near] (the same song can be queued more than once), or -1. */
    private fun nearest(tracks: List<Track>, id: String, near: Int): Int {
        var best = -1
        for (k in tracks.indices) if (tracks[k].id == id && (best < 0 || kotlin.math.abs(k - near) < kotlin.math.abs(best - near))) best = k
        return best
    }

    /**
     * Core thread only. Points the core's current index at the entry the player is on: [idx] when the core has the
     * same song there, otherwise the nearest copy of [id]. Returns the core index used (-1 when the core lacks it).
     */
    private fun alignCore(idx: Int, id: String): Int {
        val q = App.core.queueState()
        val ci = if (q.tracks.getOrNull(idx)?.id == id) idx else nearest(q.tracks, id, if (q.index >= 0) q.index else idx)
        if (ci >= 0 && ci != q.index) App.core.queueJump(ci)
        return ci
    }

    /**
     * Main thread. Makes the player's items match the core queue [q] without touching the item that is playing:
     * everything after it and before it is replaced with the core's order around the core's copy of it.
     * A no-op when both already match.
     */
    private fun reconcile(q: QueueState) {
        val p = player ?: return
        val id = p.currentMediaItem?.mediaId ?: return
        val coreIds = q.tracks.map { it.id }
        if (playerIds(p) == coreIds) return
        val cur = p.currentMediaItemIndex
        val ci = if (coreIds.getOrNull(q.index) == id) q.index else nearest(q.tracks, id, cur)
        if (ci < 0) return
        if (cur + 1 < p.mediaItemCount) p.removeMediaItems(cur + 1, p.mediaItemCount)
        val tail = q.tracks.drop(ci + 1)
        if (tail.isNotEmpty()) p.addMediaItems(tail.map(::item))
        if (cur > 0) p.removeMediaItems(0, cur)
        if (ci > 0) p.addMediaItems(0, q.tracks.take(ci).map(::item))
        onCore { alignCore(ci, id) }
    }

    /** Media-resumption (system media controls after reboot / app killed): the saved session. Off-main. */
    fun resumptionItems(): Session.Saved? = Session.restore(App.instance)

    internal fun detach() {
        if (savePending) { main.removeCallbacks(saveRunnable); savePending = false }
        finishPlay(false)
        player?.removeListener(listener)
        player = null
        ticker?.cancel()
        runCatching { controller?.release() }
        controller = null
        connecting = false
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
    internal fun itemFor(t: Track): MediaItem = item(t)

    // ------------------------------------------------------------------ commands
    fun play(ctx: Context, list: List<Track>, index: Int, source: String) = play(ctx, list, index, source, 0L)

    private fun play(ctx: Context, list: List<Track>, index: Int, source: String, startMs: Long) = ensure(ctx) {
        if (player == null || list.isEmpty()) return@ensure
        finishPlay(false)
        // optimistic: the mini player shows the tapped song right away
        list.getOrNull(index)?.let { _current.value = it }
        onCore {
            App.core.queueSet(list, index, source)
            val q = App.core.queueState()
            onMain {
                val p = player ?: return@onMain
                p.setMediaItems(q.tracks.map(::item), q.index.coerceAtLeast(0), startMs.coerceAtLeast(0L))
                p.repeatMode = repeatOf(q.repeat.toInt())
                p.prepare()
                p.play()
                _queue.value = q
                saveSession()
            }
        }
    }

    fun playShuffled(ctx: Context, list: List<Track>, source: String) = ensure(ctx) {
        if (list.isEmpty()) return@ensure
        onCore { App.core.setShuffle(true) }
        play(ctx, list, (list.indices).random(), source)
    }

    fun toggle() {
        val p = player ?: return
        // by intent, not by isPlaying: while a stream is loading / retrying isPlaying is false, and a tap must pause
        if (p.playWhenReady && p.playbackState != Player.STATE_ENDED) p.pause() else {
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
        saveSession()
    }

    fun jump(index: Int) {
        player?.seekTo(index, 0)
        player?.play()
    }

    fun setShuffle(on: Boolean) {
        if (player == null) return
        onCore {
            App.core.setShuffle(on)
            val q = App.core.queueState()
            onMain {
                rebuildTail(q)
                _queue.value = q
                saveSession()
            }
        }
    }

    fun cycleRepeat() {
        onCore {
            val r = App.core.cycleRepeat().toInt()
            val q = App.core.queueState()
            onMain {
                player?.repeatMode = repeatOf(r)
                _queue.value = q
                saveSession()
            }
        }
    }

    fun setSpeed(s: Float) {
        player?.setPlaybackSpeed(s)
        _speed.value = s
    }

    fun playNext(ctx: Context, t: Track) = ensure(ctx) {
        val p = player ?: return@ensure
        if (p.mediaItemCount == 0) return@ensure play(ctx, listOf(t), 0, "Queue")
        onCore {
            val at = App.core.queueState().index + 1
            App.core.queuePlayNext(t)
            val q = App.core.queueState()
            onMain {
                val pl = player ?: return@onMain
                pl.addMediaItem(at.coerceIn(0, pl.mediaItemCount), item(t))
                reconcile(q)
                _queue.value = q
                saveSession()
            }
        }
    }

    fun addToQueue(ctx: Context, t: Track) = ensure(ctx) {
        val p = player ?: return@ensure
        if (p.mediaItemCount == 0) return@ensure play(ctx, listOf(t), 0, "Queue")
        onCore {
            // the core inserts before the autoplay tail (or at the end); the song may already be queued, so the
            // position can't be looked up by id afterwards (that put the copy at the OLD entry and split the lists)
            val q0 = App.core.queueState()
            val at = if (q0.autoplayFrom >= 0) q0.autoplayFrom else q0.tracks.size
            App.core.queueAdd(t)
            val q = App.core.queueState()
            onMain {
                val pl = player ?: return@onMain
                pl.addMediaItem(at.coerceIn(0, pl.mediaItemCount), item(t))
                reconcile(q)
                _queue.value = q
                saveSession()
            }
        }
    }

    fun remove(index: Int) {
        val p = player ?: return
        if (index == p.currentMediaItemIndex || index !in 0 until p.mediaItemCount) return
        onCore {
            App.core.queueRemove(index)
            val q = App.core.queueState()
            onMain {
                val pl = player ?: return@onMain
                if (index < pl.mediaItemCount && index != pl.currentMediaItemIndex) pl.removeMediaItem(index)
                reconcile(q)
                _queue.value = q
                saveSession()
            }
        }
    }

    /**
     * Reorder one queue entry. [id] is the dragged song: when the queue changed under the drag (a skip moved the
     * current index), the move is re-anchored on that song so the right entry moves and the current one never does.
     */
    fun move(from: Int, to: Int, id: String? = null) {
        if (player == null) return
        onCore {
            val q0 = App.core.queueState()
            val n = q0.tracks.size
            var f = from
            if (id != null && q0.tracks.getOrNull(from)?.id != id) f = nearest(q0.tracks, id, from)
            if (f < 0 || f >= n || f == q0.index) return@onCore
            val t = (f + (to - from)).coerceIn(0, n - 1)
            if (t == f) return@onCore
            App.core.queueMove(f, t)
            val q = App.core.queueState()
            android.util.Log.i(TAG, "move $f -> $t (asked $from -> $to) id=${q0.tracks[f].id}")
            onMain {
                val pl = player ?: return@onMain
                if (f < pl.mediaItemCount && t < pl.mediaItemCount && pl.getMediaItemAt(f).mediaId == q0.tracks[f].id && f != pl.currentMediaItemIndex) pl.moveMediaItem(f, t)
                reconcile(q)
                _queue.value = q
                saveSession()
            }
        }
    }

    /**
     * More tracks of a paged playlist arrived: when that playlist is what's playing, append them to the queue
     * (before any autoplay tail, de-duplicated by id) so Play/Shuffle could start before every page was loaded.
     */
    fun appendFromSource(source: String, more: List<Track>) {
        if (more.isEmpty() || player == null) return
        onCore {
            val q0 = App.core.queueState()
            if (q0.sourceTitle != source || q0.tracks.isEmpty()) return@onCore
            val have = q0.tracks.mapTo(HashSet()) { it.id }
            val add = more.filter { have.add(it.id) }
            if (add.isEmpty()) return@onCore
            add.forEach { App.core.queueAdd(it) }
            val q = App.core.queueState()
            onMain {
                if (player == null) return@onMain
                rebuildTail(q)
                _queue.value = q
            }
        }
    }

    /** Replace everything after the current item with the core's order (after shuffle toggles). */
    private fun rebuildTail(q: QueueState) = reconcile(q)

    private fun publishQueue() {
        onCore { _queue.value = App.core.queueState() }
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
        val q = withContext(coreQ) { App.core.queueState() }
        withContext(Dispatchers.Main) {
            val p = player ?: return@withContext
            val have = p.mediaItemCount
            if (q.tracks.size > have && playerIds(p) == q.tracks.take(have).map { it.id }) p.addMediaItems(q.tracks.drop(have).map(::item))
            else reconcile(q)
            _queue.value = q
        }
    }

    // ------------------------------------------------------------------ listener
    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val skipped = reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK
            finishPlay(skipped)
            val p = player ?: return
            if (sleepAt.value == END_OF_TRACK && reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                sleepAt.value = 0L
                p.pause()
            }
            fadeIn = if (App.prefs.crossfadeSec.value > 0 && reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) 0f else 1f
            val idx = p.currentMediaItemIndex
            val mid = mediaItem?.mediaId
            android.util.Log.i(TAG, "track idx=$idx id=$mid reason=$reason playWhenReady=${p.playWhenReady}")
            onCore {
                if (mid != null) alignCore(idx, mid) else App.core.queueJump(idx)
                _queue.value = App.core.queueState()
            }
            saveSession()
            val t = trackFor(mediaItem?.mediaId)
            _current.value = t
            _duration.value = (t?.durationS?.toLong() ?: 0L) * 1000
            playStartedAt = System.currentTimeMillis() / 1000
            listenedMs = 0
            applyLoudness(mediaItem?.mediaId)
            NowPlayingWidget.refresh(App.instance)
            val nearEnd = p.mediaItemCount - p.currentMediaItemIndex <= 3
            App.scope.launch {
                t?.let { runCatching { App.core.lastfmNowPlaying(it) } }
                if (nearEnd) extendAutoplay()
                prefetchUpcoming()
            }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            android.util.Log.i(TAG, "playWhenReady=$playWhenReady reason=$reason")
            publishPlaying()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            publishPlaying()
            if (!isPlaying) saveSession()
            lastTick = System.currentTimeMillis()
            NowPlayingWidget.refresh(App.instance)
        }

        override fun onPlaybackStateChanged(state: Int) {
            _buffering.value = state == Player.STATE_BUFFERING
            publishPlaying()
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
                            // resume only if the user still wants playback (a pause while resolving sticks)
                            val resume = it.playWhenReady
                            it.prepare()
                            it.seekTo(idx, pos)
                            if (resume) it.play()
                        }
                        delay(15_000)
                        Resolver.overrides.remove(id)
                    }
                }
            } else {
                _error.value = "Couldn't play this track ($msg). Skipping."
                // paused: stay on the failing track (no skipping through the queue while nothing should play)
                if (p.playWhenReady && p.hasNextMediaItem()) {
                    val resume = p.playWhenReady
                    p.seekToNextMediaItem()
                    p.prepare()
                    if (resume) p.play()
                }
            }
        }
    }

    /** Play/pause buttons follow the user's intent (Pause stays shown while a stream loads or retries). */
    private fun publishPlaying() {
        val p = player ?: return
        _playing.value = p.playWhenReady && p.playbackState != Player.STATE_ENDED
    }

    private fun applyLoudness(id: String?) {
        val p = player ?: return
        if (!App.prefs.normalize.value) {
            p.volume = 1f
            return
        }
        p.volume = baseVolume(id)
    }

    private fun baseVolume(id: String?): Float {
        if (!App.prefs.normalize.value) return 1f
        val db = id?.let { Resolver.loudness[it] } ?: 0f
        // YouTube reports loudness relative to its -14 LUFS target; attenuate louder tracks only.
        return if (db > 0f) 10f.pow(-db / 20f).coerceIn(0.2f, 1f) else 1f
    }

    /** Fade out the end of a track and fade the next one in (Settings › Playback › Fade between songs). */
    private fun applyFade(p: ExoPlayer) {
        val cf = App.prefs.crossfadeSec.value
        val base = baseVolume(p.currentMediaItem?.mediaId)
        if (cf <= 0) {
            if (p.volume != base) p.volume = base
            return
        }
        val win = cf * 1000L
        val rem = p.duration.takeIf { it > 0 }?.let { it - p.currentPosition } ?: Long.MAX_VALUE
        fadeIn = (fadeIn + 250f / win).coerceAtMost(1f)
        val out = if (rem < win && p.hasNextMediaItem()) (rem.toFloat() / win).coerceIn(0f, 1f) else 1f
        p.volume = base * minOf(fadeIn, out)
    }

    private fun tickSleep(p: ExoPlayer) {
        val at = sleepAt.value
        if (at <= 0L) return
        val left = at - System.currentTimeMillis()
        if (left <= 0) {
            sleepAt.value = 0L
            p.pause()
            p.volume = baseVolume(p.currentMediaItem?.mediaId)
        } else if (left < 10_000) {
            p.volume = baseVolume(p.currentMediaItem?.mediaId) * (left / 10_000f)
        }
    }

    /** Saves the current track + position every few seconds for Home › Continue listening. */
    private fun saveResume(p: ExoPlayer) {
        val now = System.currentTimeMillis()
        if (now - lastResumeSave < 5_000) return
        lastResumeSave = now
        saveSession()
        val t = _current.value ?: return
        if (t.id.startsWith("local:")) return
        Resume.save(t, p.currentPosition, p.duration)
    }

    fun playFrom(ctx: Context, t: Track, positionMs: Long) = play(ctx, listOf(t), 0, "Continue listening", positionMs)

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
                    applyFade(p)
                    tickSleep(p)
                    saveResume(p)
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
        App.scope.launch {
            runCatching { App.core.recordPlay(t, playStartedAt, ms.toULong(), wasSkip, tz, "android") }
            runCatching { App.core.lastfmFlush() } // offline-safe: stays queued in the DB on failure
        }
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
    private const val TAG = "itune-session"
    private const val SAVE_COALESCE_MS = 800L
}
