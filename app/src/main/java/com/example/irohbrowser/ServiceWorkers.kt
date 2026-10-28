package com.example.irohbrowser

import android.net.Uri
import android.util.Log
import android.webkit.ServiceWorkerClient
import android.webkit.ServiceWorkerController
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream

/** A check on one request: null lets it through, a response refuses it. */
typealias RequestCheck = (url: String?) -> WebResourceResponse?

/**
 * The check on the requests of the app's pages: one to a proxy among
 * [allowed] passes, and one to anywhere else is refused -- where it would
 * leave the device, or take an endpoint's cookies to whatever else is
 * listening. See [Origins.mayRequest]. A card's page may reach its own proxy;
 * the service workers, every one running.
 *
 * The page sees only the 403, so logcat says what was refused.
 */
fun requestCheck(allowed: () -> Collection<ProxyBinding>): RequestCheck = { url ->
    if (Origins.mayRequest(url, allowed())) {
        null
    } else {
        Log.i(LOG_TAG, "refused a request to ${forLog(url)}")
        WebResourceResponse(
            "text/plain",
            "utf-8",
            403,
            "Forbidden",
            mapOf("Cache-Control" to "no-store"),
            ByteArrayInputStream("Refused: not the origin of a running endpoint.".toByteArray()),
        )
    }
}

/** The scheme and host of [url], for a log line: the rest may hold a token. */
internal fun forLog(url: String?): String =
    runCatching { Uri.parse(url) }.getOrNull()
        ?.let { listOfNotNull(it.scheme, it.host).joinToString("://") }
        ?: "?"

/**
 * Where a page's service workers send their requests.
 *
 * They do not pass through the WebViewClient, so the loopback check has to be
 * installed here as well. An interface because the controller is the WebView
 * provider's, which a Robolectric test does not have.
 */
fun interface ServiceWorkerRequests {
    /** Check every service worker request with [check]; null removes the check. */
    fun route(check: RequestCheck?)
}

/** The real one. Process-wide, like the controller it sets. */
object PlatformServiceWorkerRequests : ServiceWorkerRequests {
    override fun route(check: RequestCheck?) {
        ServiceWorkerController.getInstance().setServiceWorkerClient(
            check?.let {
                object : ServiceWorkerClient() {
                    override fun shouldInterceptRequest(request: android.webkit.WebResourceRequest): WebResourceResponse? =
                        it(request.url?.toString())
                }
            },
        )
    }
}
