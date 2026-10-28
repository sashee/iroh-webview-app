package com.example.irohbrowser

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri

/*
 * Every endpoint in a card of its own -- its own task, listed apart in
 * Android's recent apps -- with its own page and proxy, so that one plays
 * music while another is used, and switching between them is the system's.
 *
 * A card is a MainActivity opened by a document intent naming its endpoint by
 * label, bound to that endpoint for its life. Android keeps one task per such
 * intent, so opening an endpoint's card again -- from the list, the
 * notification, the launcher -- brings back the one there is. The launcher's
 * own task is only a way in (EndpointCards.isLauncher): it opens the card of
 * the endpoint used last, and goes.
 */

/** The intents that open cards. */
object EndpointCards {
    private const val SCHEME = "iroh-endpoint"

    /**
     * Opens [label]'s card: the one there is, as it was left, or a new one.
     * Android finds it by the intent's data, so this is the only way one is
     * opened.
     */
    fun intent(context: Context, label: String): Intent = document(context, label)

    /** Opens the card for adding an endpoint, for when there is none to open. One of it too. */
    fun entry(context: Context): Intent = document(context, "")

    /** Opens the card of the endpoint used last, or the entry when there is none. */
    fun lastUsed(context: Context, endpoints: Endpoints, identify: (String) -> EndpointIdentity?): Intent =
        endpoints.selected?.let { identify(it.ticket) }?.let { intent(context, it.label) } ?: entry(context)

    /** The endpoint a card was opened for; null for the entry, and for anything else. */
    fun labelOf(intent: Intent?): String? =
        intent?.data?.takeIf { it.scheme == SCHEME }?.schemeSpecificPart?.takeIf { it.isNotBlank() }

    /**
     * Whether [intent] is the launcher's, which opens no card of its own but
     * [lastUsed]'s. Its task would otherwise be a card Android can't tell
     * apart from the endpoint's own, which would get another.
     */
    fun isLauncher(intent: Intent?): Boolean =
        intent?.action == Intent.ACTION_MAIN && intent.data == null

    private fun document(context: Context, label: String): Intent =
        Intent(context, MainActivity::class.java)
            .setData(Uri.fromParts(SCHEME, label, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT)
}

/** Closing other cards: the platform's in the app, recorded by tests. */
fun interface CardTasks {
    /**
     * Close the card of [label]'s endpoint, whether its page is open or it is
     * only listed in the recent apps.
     */
    fun close(context: Context, label: String)
}

/** The real one: the app's own tasks, which it may close without a permission. */
object PlatformCardTasks : CardTasks {
    override fun close(context: Context, label: String) {
        context.getSystemService(ActivityManager::class.java).appTasks
            .filter { EndpointCards.labelOf(it.taskInfo?.baseIntent) == label }
            .forEach { it.finishAndRemoveTask() }
    }
}
