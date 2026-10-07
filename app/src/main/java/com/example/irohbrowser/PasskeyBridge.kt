package com.example.irohbrowser

import org.json.JSONObject

/**
 * The app's end of the bridge the injected script talks to.
 *
 * One message in, at most one reply out, carrying the request's id back. The
 * only state is the ceremony in progress: one at a time, because a second
 * prompt stacked on the first would leave the user unsure which request a
 * finger on the sensor answers.
 *
 * The sender's origin is checked again here even though the WebView only
 * delivers messages from the origin the bridge was installed for. If that ever
 * stopped being true, this is what keeps another origin's page from reaching
 * this endpoint's passkeys.
 */
class PasskeyBridge(private val authenticator: PasskeyAuthenticator) {

    private class Ceremony(val id: Int) {
        var cancel: () -> Unit = {}
    }

    private var inProgress: Ceremony? = null

    fun receive(message: String, sourceOrigin: String, site: PasskeySite, reply: (String) -> Unit) {
        val envelope = runCatching { JSONObject(message) }.getOrNull() ?: return
        val id = envelope.optInt("id", -1).takeIf { it >= 0 } ?: return
        val options = envelope.optJSONObject("options")
        when (envelope.text("type")) {
            "cancel" -> if (inProgress?.id == id) cancel()
            "create" -> start(id, sourceOrigin, site, reply) { done ->
                when (val parsed = PasskeyRequests.parseCreate(options)) {
                    is Outcome.Ok -> authenticator.register(parsed.value, site, done)
                    is Outcome.Failed -> {
                        done(parsed)
                        NOTHING_TO_CANCEL
                    }
                }
            }
            "get" -> start(id, sourceOrigin, site, reply) { done ->
                when (val parsed = PasskeyRequests.parseGet(options)) {
                    is Outcome.Ok -> authenticator.signIn(parsed.value, site, done)
                    is Outcome.Failed -> {
                        done(parsed)
                        NOTHING_TO_CANCEL
                    }
                }
            }
            else -> reply(replyJson(id, Outcome.Failed(PasskeyError.type("Unknown request."))))
        }
    }

    /** Withdraw whatever is in progress. For a switch of endpoint, and for the activity going away. */
    fun cancel() {
        val ceremony = inProgress ?: return
        inProgress = null
        ceremony.cancel()
    }

    private fun start(
        id: Int,
        sourceOrigin: String,
        site: PasskeySite,
        reply: (String) -> Unit,
        run: ((Outcome<JSONObject>) -> Unit) -> () -> Unit,
    ) {
        if (!Origins.isOwnOrigin(sourceOrigin, site.label, site.port)) {
            return reply(replyJson(id, Outcome.Failed(PasskeyError.security("Not this endpoint's page."))))
        }
        if (inProgress != null) {
            return reply(replyJson(id, Outcome.Failed(PasskeyError.notAllowed("A request is already pending."))))
        }
        val ceremony = Ceremony(id)
        inProgress = ceremony
        // `run` may finish before it returns -- every refusal that needs no
        // prompt does -- in which case the ceremony has already let go.
        ceremony.cancel = run { outcome ->
            if (inProgress === ceremony) inProgress = null
            reply(replyJson(id, outcome))
        }
    }

    companion object {
        /** The name of the object the WebView injects into pages; the script looks for it. */
        const val NAME = "__irohPasskeys"

        /** The script, in the APK's assets. */
        const val SCRIPT_ASSET = "passkeys.js"

        private val NOTHING_TO_CANCEL: () -> Unit = {}

        fun replyJson(id: Int, outcome: Outcome<JSONObject>): String = when (outcome) {
            is Outcome.Ok -> JSONObject().put("id", id).put("credential", outcome.value)
            is Outcome.Failed -> JSONObject()
                .put("id", id)
                .put("error", JSONObject().put("name", outcome.error.name).put("message", outcome.error.message))
        }.toString()
    }
}
