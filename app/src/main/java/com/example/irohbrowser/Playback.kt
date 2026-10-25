package com.example.irohbrowser

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log

/**
 * Showing the system what a page plays: a media session, and its notification.
 * A seam, like the others in [AppContainer]: tests install their own.
 */
interface MediaPlayback {

    /**
     * Show [media]. The buttons the user presses come to [press], with the
     * position to seek to for [MediaAction.SeekTo]. Nothing is shown until the
     * page plays, so a page that only sets metadata gets no notification.
     */
    fun show(media: PageMedia, press: (MediaAction, Long?) -> Unit)

    /** Stop showing anything: the page went, or stopped playing long ago. */
    fun end()

    /** Whether something is shown, which keeps the app running in the background. */
    val active: Boolean
}

/**
 * [MediaPlayback] with Android's own media session. On Android 13 and later,
 * the notification's buttons, the lock screen's and the headset's all come
 * from the session's [PlaybackState], so the session is the whole interface;
 * [PlaybackService] holds the notification, which keeps the process, and so
 * the page, running while the app is in the background.
 *
 * Paused for [PAUSED_LIMIT_MS], it ends: the service stops, the notification
 * goes, and Android may freeze the app again. The page itself stays paused.
 */
class AndroidMediaPlayback(context: Context) : MediaPlayback {

    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val notifications get() = context.getSystemService(NotificationManager::class.java)
    private val endWhenIdle = Runnable { end() }

    private var session: MediaSession? = null
    private var press: (MediaAction, Long?) -> Unit = { _, _ -> }

    /** What the session shows, so only changes are passed on: the system UI warns about the rest. */
    private var shown: PageMedia? = null
    private var shownState: SessionState? = null

    override val active: Boolean
        get() = session != null

    override fun show(media: PageMedia, press: (MediaAction, Long?) -> Unit) {
        this.press = press
        val opened = session
        val current = opened ?: if (media.playing) open() else return
        val before = shown
        val state = MediaSessionBridge.sessionState(media)
        if (before == null || !sameMetadata(before, media)) current.setMetadata(metadataOf(media))
        if (state != shownState) current.setPlaybackState(playbackStateOf(state))
        when {
            opened == null -> startService(current, media)
            before == null || !sameMetadata(before, media) || before.playing != media.playing ->
                notifications.notify(NOTIFICATION_ID, notification(context, current.sessionToken, media))
        }
        shown = media
        shownState = state
        main.removeCallbacks(endWhenIdle)
        if (!media.playing) main.postDelayed(endWhenIdle, PAUSED_LIMIT_MS)
    }

    override fun end() {
        main.removeCallbacks(endWhenIdle)
        val ending = session ?: return
        session = null
        shown = null
        shownState = null
        ending.isActive = false
        ending.release()
        // Asked rather than stopped: a service stopped before it has called
        // startForeground crashes the app, and its start may still be queued.
        // Intents arrive in order, so this one comes after it.
        runCatching { context.startService(Intent(context, PlaybackService::class.java).setAction(PlaybackService.ACTION_STOP)) }
        notifications.cancel(NOTIFICATION_ID)
    }

    private fun open(): MediaSession = MediaSession(context, LOG_TAG).apply {
        setCallback(object : MediaSession.Callback() {
            override fun onPlay() = press(MediaAction.Play, null)
            override fun onPause() = press(MediaAction.Pause, null)
            override fun onStop() = press(MediaAction.Stop, null)
            override fun onSkipToNext() = press(MediaAction.Next, null)
            override fun onSkipToPrevious() = press(MediaAction.Previous, null)
            override fun onSeekTo(pos: Long) = press(MediaAction.SeekTo, pos)
            override fun onRewind() = press(MediaAction.SeekBackward, null)
            override fun onFastForward() = press(MediaAction.SeekForward, null)
        })
        openApp(context)?.let { setSessionActivity(it) }
        isActive = true
        session = this
    }

    private fun startService(current: MediaSession, media: PageMedia) {
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.playback_channel), NotificationManager.IMPORTANCE_LOW),
        )
        val intent = Intent(context, PlaybackService::class.java)
            .putExtra(PlaybackService.EXTRA_TOKEN, current.sessionToken)
            .putExtra(PlaybackService.EXTRA_TITLE, media.title)
        // Android refuses to start a foreground service from the background. A
        // page that starts playing on its own while the app is hidden gets its
        // session, but no notification, until the app is opened again.
        runCatching { context.startForegroundService(intent) }
            .onFailure { Log.w(LOG_TAG, "no playback notification: ${it.javaClass.simpleName}") }
    }

    private fun metadataOf(media: PageMedia): MediaMetadata = MediaMetadata.Builder()
        .putString(MediaMetadata.METADATA_KEY_TITLE, media.title)
        .putString(MediaMetadata.METADATA_KEY_ARTIST, media.artist)
        .putString(MediaMetadata.METADATA_KEY_ALBUM, media.album)
        .apply { media.durationMs?.let { putLong(MediaMetadata.METADATA_KEY_DURATION, it) } }
        .build()

    private fun sameMetadata(a: PageMedia, b: PageMedia) =
        a.title == b.title && a.artist == b.artist && a.album == b.album && a.durationMs == b.durationMs

    private fun playbackStateOf(state: SessionState): PlaybackState = PlaybackState.Builder()
        .setActions(state.actions)
        .setState(state.state, state.positionMs, state.speed, SystemClock.elapsedRealtime())
        .build()

    companion object {
        const val PAUSED_LIMIT_MS = 10 * 60 * 1000L
        const val CHANNEL_ID = "playback"
        const val NOTIFICATION_ID = 1

        /** The notification for [media] in the session [token]; its buttons are the session's. */
        fun notification(context: Context, token: MediaSession.Token, media: PageMedia): Notification =
            Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(media.title.ifEmpty { context.getString(R.string.app_name) })
                .setContentText(listOf(media.artist, media.album).filter { it.isNotEmpty() }.joinToString(" · "))
                .setContentIntent(openApp(context))
                .setStyle(Notification.MediaStyle().setMediaSession(token))
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOngoing(media.playing)
                .build()

        /** Bringing the app back as the launcher does: its task, as it was left. */
        private fun openApp(context: Context): PendingIntent? =
            context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
                PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE)
            }
    }
}

/**
 * The foreground service that holds the playback notification. It does
 * nothing else: what plays is the page's, in the activity's WebView, and the
 * buttons are [AndroidMediaPlayback]'s session's. Being a `mediaPlayback`
 * foreground service is what keeps Android from freezing the app, and the page
 * with it, while the screen is off.
 */
class PlaybackService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val token = intent?.getParcelableExtra(EXTRA_TOKEN, MediaSession.Token::class.java)
        if (token == null) {
            // Restarted by the system after the process died: the page that
            // played is gone, so there is nothing to show.
            stopSelf()
            return START_NOT_STICKY
        }
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val media = PageMedia(title, "", "", playing = true, actions = emptySet(), positionMs = null, durationMs = null, speed = 1f)
        startForeground(
            AndroidMediaPlayback.NOTIFICATION_ID,
            AndroidMediaPlayback.notification(this, token, media),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
        )
        return START_NOT_STICKY
    }

    /** Swiped away from the recents screen: the page, and what it played, went with the activity. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        AppContainer.get(this).media.end()
        stopSelf()
    }

    companion object {
        const val ACTION_STOP = "com.example.irohbrowser.STOP_PLAYBACK"
        const val EXTRA_TOKEN = "token"
        const val EXTRA_TITLE = "title"
    }
}
