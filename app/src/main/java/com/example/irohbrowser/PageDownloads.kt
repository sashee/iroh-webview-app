package com.example.irohbrowser

import java.util.concurrent.Executor
import org.json.JSONObject

/** What a page's `assets/downloads.js` sends about a file it saves. */
sealed interface DownloadMessage {
    val id: Int

    /** A file of [size] bytes, to be saved as [name] (already cleaned). */
    data class Start(override val id: Int, val name: String, val size: Long) : DownloadMessage

    /** Every byte has been sent. */
    data class End(override val id: Int) : DownloadMessage

    /** The page could not read the rest. */
    data class Abort(override val id: Int) : DownloadMessage
}

/** What the user is told about a file a page saves. */
sealed interface DownloadEvent {
    val name: String

    data class Started(override val name: String) : DownloadEvent
    data class Saved(override val name: String) : DownloadEvent
    data class Failed(override val name: String) : DownloadEvent
}

/** Where saved files go: the seam the platform's Downloads collection sits behind. */
fun interface DownloadTarget {
    /** A new, unfinished file to be called [name]; null when none could be made. */
    fun create(name: String): DownloadFile?
}

/** A file being saved. Not visible to anyone until [finish]. Its calls may throw. */
interface DownloadFile {
    fun write(bytes: ByteArray)

    /** Make the file visible, under the name it ended up with: a taken one gets a number. */
    fun finish(): String

    /** Delete what was written. */
    fun discard()
}

/**
 * The download bridge: files a page holds -- a `blob:` URL, which the system
 * downloader cannot fetch -- sent by `assets/downloads.js` to be saved in
 * Downloads.
 *
 * The protocol, all of it for one file at a time:
 * - text `{"id":1,"start":{"name":"a.mp3","size":3}}`, answered with
 *   `{"id":1,"received":0}`: send from there;
 * - binary pieces, up to [MAX_PIECE] bytes each, each answered with how many
 *   bytes have been written, `{"id":1,"received":3}`;
 * - text `{"id":1,"end":true}`, answered with the name the file was saved as,
 *   `{"id":1,"saved":"a (1).mp3"}`;
 * - or text `{"id":1,"abort":true}`, when the page cannot read on.
 * Any of them may be answered `{"id":1,"error":"…"}`, which ends that file.
 *
 * As narrow as the other three: from the endpoint's own origin only, a name
 * and bytes in, counts out. It gives a page nothing new: it could already
 * have its server send any file to Downloads through the system downloader.
 */
object DownloadBridge {

    /** The name of the object the WebView injects into pages; the script looks for it. */
    const val NAME = "__irohDownloads"

    /** The script, in the APK's assets. */
    const val SCRIPT_ASSET = "downloads.js"

    /** The largest piece taken. The script sends 1 MiB. */
    const val MAX_PIECE = 4 * 1024 * 1024

    /** What [message] says; null when it makes no sense. */
    fun parse(message: String): DownloadMessage? {
        val root = runCatching { JSONObject(message) }.getOrNull() ?: return null
        val id = (root.opt("id") as? Int)?.takeIf { it >= 0 } ?: return null
        val start = root.optJSONObject("start")
        return when {
            start != null -> {
                val size = (start.opt("size") as? Number)
                    ?.takeIf { it.toDouble().let { size -> size >= 0 && size == Math.floor(size) && size <= Long.MAX_VALUE } }
                    ?.toLong()
                    ?: return null
                DownloadMessage.Start(id, Downloads.cleanName(start.opt("name") as? String), size)
            }
            root.opt("end") == true -> DownloadMessage.End(id)
            root.opt("abort") == true -> DownloadMessage.Abort(id)
            else -> null
        }
    }

    fun received(id: Int, bytes: Long): String = JSONObject().put("id", id).put("received", bytes).toString()

    fun saved(id: Int, name: String): String = JSONObject().put("id", id).put("saved", name).toString()

    fun error(id: Int, why: String): String = JSONObject().put("id", id).put("error", why).toString()
}

/**
 * The file a page is saving, if any, and what happens to it.
 *
 * Messages arrive on the main thread, and that is where this lives. All that
 * touches the file runs on [io], in order, so a write never blocks the page,
 * and the answers come back through [main]. The page sends a piece only once
 * the one before is written, so at most one is held here at a time.
 *
 * One file at a time, for all pages: a second is refused while one is saved.
 */
class PageDownloads(
    private val target: DownloadTarget,
    private val io: Executor,
    private val main: Executor,
    private val tell: (DownloadEvent) -> Unit,
) {
    private class Transfer(val id: Int, val name: String, val size: Long, val reply: (String) -> Unit) {
        /** The file, once [io] has made it. Only touched on [io]. */
        var file: DownloadFile? = null

        // The rest only on the main thread.
        var ready = false
        var writing = false
        var received = 0L
    }

    /**
     * The file being saved. The one deliberate piece of state here: pieces
     * arrive one message at a time, and something has to know what they
     * belong to.
     */
    private var current: Transfer? = null

    /** A text message from a page of [site]. */
    fun receive(message: String, sourceOrigin: String, site: ProxyBinding, reply: (String) -> Unit) {
        if (!Origins.isOwnOrigin(sourceOrigin, site.label, site.port)) return
        when (val parsed = DownloadBridge.parse(message)) {
            null -> Unit
            is DownloadMessage.Start -> start(parsed, reply)
            is DownloadMessage.End -> current?.takeIf { it.id == parsed.id }?.let(::end)
            is DownloadMessage.Abort -> current?.takeIf { it.id == parsed.id }?.let { fail(it, why = null) }
        }
    }

    /** A piece of the file being saved, from a page of [site]. */
    fun receive(bytes: ByteArray, sourceOrigin: String, site: ProxyBinding) {
        if (!Origins.isOwnOrigin(sourceOrigin, site.label, site.port)) return
        val transfer = current ?: return
        val refusal = when {
            !transfer.ready || transfer.writing -> "A piece came before the last one was written."
            bytes.size > DownloadBridge.MAX_PIECE -> "A piece was too large."
            bytes.size > transfer.size - transfer.received -> "More bytes came than the file has."
            else -> null
        }
        if (refusal != null) return fail(transfer, refusal)
        transfer.writing = true
        transfer.received += bytes.size
        val received = transfer.received
        io.execute {
            val written = runCatching { transfer.file!!.write(bytes) }.isSuccess
            main.execute answer@{
                if (current !== transfer) return@answer
                transfer.writing = false
                if (written) transfer.reply(DownloadBridge.received(transfer.id, received)) else fail(transfer, "The file could not be written.")
            }
        }
    }

    /** The page went away, or its endpoint did: what it was saving is not saved. */
    fun cancel() {
        current?.let { fail(it, why = null) }
    }

    private fun start(message: DownloadMessage.Start, reply: (String) -> Unit) {
        if (current != null) {
            reply(DownloadBridge.error(message.id, "Another download is being saved."))
            tell(DownloadEvent.Failed(message.name))
            return
        }
        val transfer = Transfer(message.id, message.name, message.size, reply)
        current = transfer
        tell(DownloadEvent.Started(transfer.name))
        io.execute {
            val file = runCatching { target.create(transfer.name) }.getOrNull()
            transfer.file = file
            main.execute answer@{
                // Cancelled meanwhile: the cancel's own task, after this one, deletes the file.
                if (current !== transfer) return@answer
                if (file == null) return@answer fail(transfer, "The file could not be created.")
                transfer.ready = true
                reply(DownloadBridge.received(transfer.id, 0))
            }
        }
    }

    private fun end(transfer: Transfer) {
        if (!transfer.ready || transfer.writing || transfer.received != transfer.size) {
            return fail(transfer, "The download ended before the whole file came.")
        }
        current = null
        io.execute {
            val saved = runCatching { transfer.file!!.finish() }.getOrNull()
            if (saved == null) runCatching { transfer.file?.discard() }
            main.execute {
                if (saved != null) {
                    transfer.reply(DownloadBridge.saved(transfer.id, saved))
                    tell(DownloadEvent.Saved(saved))
                } else {
                    transfer.reply(DownloadBridge.error(transfer.id, "The file could not be saved."))
                    tell(DownloadEvent.Failed(transfer.name))
                }
            }
        }
    }

    /** Ends [transfer] unsaved, telling the page [why] unless it is the one that ended it, or gone. */
    private fun fail(transfer: Transfer, why: String?) {
        if (current === transfer) current = null
        io.execute { runCatching { transfer.file?.discard() } }
        why?.let { transfer.reply(DownloadBridge.error(transfer.id, it)) }
        tell(DownloadEvent.Failed(transfer.name))
    }
}
