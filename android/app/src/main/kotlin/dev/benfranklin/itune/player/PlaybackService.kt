package dev.benfranklin.itune.player

import dev.benfranklin.itune.App
import dev.benfranklin.itune.R
import kotlinx.coroutines.launch

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import dev.benfranklin.itune.MainActivity

/**
 * Foreground media service (type mediaPlayback). Media3 promotes it to the foreground with a media notification
 * while playing, which also drives the lock-screen controls. Wake + Wi-Fi locks via WAKE_MODE_NETWORK.
 */
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val load = DefaultLoadControl.Builder()
            // generous buffer so short network drops don't interrupt playback
            .setBufferDurationsMs(30_000, 180_000, 1_500, 3_000)
            .build()
        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DataSources.playerFactory(this)))
            .setLoadControl(load)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("open_now_playing", true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, player).setSessionActivity(open).setCallback(callback).build()
        Playback.attach(player)
        player.addListener(object : androidx.media3.common.Player.Listener {
            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) = updateLikeButton()
        })
        likeJob = App.scope.launch(kotlinx.coroutines.Dispatchers.Main) { dev.benfranklin.itune.data.Likes.ids.collect { updateLikeButton() } }
    }

    private var likeJob: kotlinx.coroutines.Job? = null
    private val likeCmd = androidx.media3.session.SessionCommand("dev.benfranklin.itune.LIKE", android.os.Bundle.EMPTY)

    /** Like / unlike button on the lock screen and the media notification. */
    private fun updateLikeButton() {
        val s = session ?: return
        val id = s.player.currentMediaItem?.mediaId ?: return
        val liked = dev.benfranklin.itune.data.Likes.isLiked(id)
        val btn = androidx.media3.session.CommandButton.Builder()
            .setDisplayName(if (liked) "Unlike" else "Like")
            .setIconResId(if (liked) R.drawable.ic_heart_filled else R.drawable.ic_heart)
            .setSessionCommand(likeCmd)
            .build()
        runCatching { s.setCustomLayout(listOf(btn)) }
    }

    private val callback = object : MediaSession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            val cmds = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon().add(likeCmd).build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session).setAvailableSessionCommands(cmds).build()
        }

        /** System "resume" (media resumption after the app was killed): hand back the saved session, paused. */
        override fun onPlaybackResumption(mediaSession: MediaSession, controller: MediaSession.ControllerInfo): com.google.common.util.concurrent.ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            // the core DB read runs off the main thread; nothing autoplays (the system asks to resume, paused)
            val f = com.google.common.util.concurrent.SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
            App.scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                val saved = runCatching { Playback.resumptionItems() }.getOrNull()
                if (saved == null) f.setException(UnsupportedOperationException("nothing to resume"))
                else f.set(MediaSession.MediaItemsWithStartPosition(saved.tracks.map { Playback.itemFor(it) }, saved.index, saved.positionMs))
            }
            return f
        }

        override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo, customCommand: androidx.media3.session.SessionCommand, args: android.os.Bundle): com.google.common.util.concurrent.ListenableFuture<androidx.media3.session.SessionResult> {
            if (customCommand.customAction == likeCmd.customAction) {
                val id = session.player.currentMediaItem?.mediaId
                Playback.trackFor(id)?.let { dev.benfranklin.itune.data.Likes.toggle(it) }
                updateLikeButton()
            }
            return com.google.common.util.concurrent.Futures.immediateFuture(androidx.media3.session.SessionResult(androidx.media3.session.SessionResult.RESULT_SUCCESS))
        }
    }

    /** Android 12+/15: a foreground start can be refused while in the background; never crash on it. */
    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        try {
            super.onUpdateNotification(session, startInForegroundRequired)
        } catch (e: Exception) {
            android.util.Log.w("iTune", "notification update refused", e)
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /** Swiping the app away keeps the music going; only stop when nothing is playing. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        Playback.saveSessionNow("task removed")
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0 || p.playbackState == Player.STATE_ENDED) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        likeJob?.cancel()
        Playback.saveSessionNow("service destroy")
        Playback.detach()
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }
}
