package com.example.irohbrowser

import android.media.session.PlaybackState
import org.json.JSONObject

/**
 * The buttons a page's Media Session can have that the app relays, by the
 * names the web gives them.
 */
enum class MediaAction(val web: String) {
    Play("play"),
    Pause("pause"),
    Stop("stop"),
    Previous("previoustrack"),
    Next("nexttrack"),
    SeekTo("seekto"),
    SeekBackward("seekbackward"),
    SeekForward("seekforward"),
    ;

    companion object {
        fun of(web: String): MediaAction? = entries.firstOrNull { it.web == web }
    }
}

/** What a page says it is playing, from its Media Session. Times in milliseconds. */
data class PageMedia(
    val title: String,
    val artist: String,
    val album: String,
    val playing: Boolean,
    /** The buttons the page has handlers for. Play and pause work without one. */
    val actions: Set<MediaAction>,
    /** Null when the page says nothing about where it is. */
    val positionMs: Long?,
    /** Null when unknown, or for a live stream. */
    val durationMs: Long?,
    val speed: Float,
)

/** A page's message: what it plays now, or that it plays nothing. */
sealed interface MediaReport {
    data class Current(val media: PageMedia) : MediaReport
    data object Nothing : MediaReport
}

/** What Android's media session shows for a page: its state, position, speed and buttons. */
data class SessionState(val state: Int, val positionMs: Long, val speed: Float, val actions: Long)

/**
 * The media session bridge: what a page plays, relayed from its Media Session
 * by `assets/media-session.js`, and the system's buttons, sent back.
 *
 * As narrow as the other two: from the endpoint's own origin only, titles, a
 * state, a position and a list of buttons in; the name of a button, and a time
 * to seek to, out. Nothing is answered: a page learns of a press only through
 * its own handler.
 */
object MediaSessionBridge {

    /** The name of the object the WebView injects into pages; the script looks for it. */
    const val NAME = "__irohMediaSession"

    /** The script, in the APK's assets. */
    const val SCRIPT_ASSET = "media-session.js"

    /** The longest title, artist or album shown; the rest is cut. */
    const val MAX_TEXT = 200

    /**
     * What a page's [message] says, when it comes from the running endpoint's
     * own origin and makes sense; null otherwise.
     */
    fun parse(message: String, sourceOrigin: String, site: ProxyBinding): MediaReport? {
        if (!Origins.isOwnOrigin(sourceOrigin, site.label, site.port)) return null
        val root = runCatching { JSONObject(message) }.getOrNull() ?: return null
        if (!root.has("metadata") || root.isNull("metadata")) return MediaReport.Nothing
        val metadata = root.optJSONObject("metadata") ?: return null
        val position = root.optJSONObject("position")
        val durationMs = position?.let { millis(it.opt("duration")) }?.takeIf { it > 0 }
        val positionMs = position?.let { millis(it.opt("position")) }?.let { at -> durationMs?.let { at.coerceAtMost(it) } ?: at }
        val actions = root.optJSONArray("actions")
            ?.let { list -> (0 until list.length()).mapNotNull { (list.opt(it) as? String)?.let(MediaAction::of) } }
            .orEmpty()
            .toSet()
        return MediaReport.Current(
            PageMedia(
                title = text(metadata.opt("title")),
                artist = text(metadata.opt("artist")),
                album = text(metadata.opt("album")),
                playing = root.optBoolean("playing", false),
                actions = actions,
                positionMs = positionMs,
                durationMs = durationMs,
                speed = (position?.opt("rate") as? Number)?.toFloat()?.takeIf { it.isFinite() && it != 0f } ?: 1f,
            ),
        )
    }

    /** The message that presses [action] in the page; [seekToMs] is where a [MediaAction.SeekTo] goes. */
    fun command(action: MediaAction, seekToMs: Long? = null): String =
        JSONObject()
            .put("action", action.web)
            .apply { if (action == MediaAction.SeekTo && seekToMs != null) put("seekTime", seekToMs / 1000.0) }
            .toString()

    /**
     * What Android shows for [media]. Play and pause are always offered, as a
     * browser plays and pauses the media itself when the page has no handler;
     * the rest only when the page handles them. Seeking needs a duration too,
     * or there is no bar to seek on.
     */
    fun sessionState(media: PageMedia): SessionState {
        val offered = mapOf(
            MediaAction.Stop to PlaybackState.ACTION_STOP,
            MediaAction.Previous to PlaybackState.ACTION_SKIP_TO_PREVIOUS,
            MediaAction.Next to PlaybackState.ACTION_SKIP_TO_NEXT,
            MediaAction.SeekBackward to PlaybackState.ACTION_REWIND,
            MediaAction.SeekForward to PlaybackState.ACTION_FAST_FORWARD,
        ).filterKeys { it in media.actions }.values +
            listOfNotNull(PlaybackState.ACTION_SEEK_TO.takeIf { MediaAction.SeekTo in media.actions && media.durationMs != null })
        val always = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE
        return SessionState(
            state = if (media.playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
            positionMs = media.positionMs ?: PlaybackState.PLAYBACK_POSITION_UNKNOWN,
            speed = media.speed,
            actions = offered.fold(always) { all, action -> all or action },
        )
    }

    /** Seconds from the page, as milliseconds; null for anything that is not a time. */
    private fun millis(value: Any?): Long? =
        (value as? Number)?.toDouble()?.takeIf { it.isFinite() && it >= 0 }?.let { (it * 1000).toLong() }

    /** Text from the page, without control characters, and cut to [MAX_TEXT]. */
    private fun text(value: Any?): String =
        ((value as? String) ?: "").filterNot { it.isISOControl() }.trim().take(MAX_TEXT)
}
