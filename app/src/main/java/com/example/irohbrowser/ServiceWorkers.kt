package com.example.irohbrowser

import android.webkit.ServiceWorkerClient
import android.webkit.ServiceWorkerController
import android.webkit.WebResourceResponse

/** A check on one request: null lets it through, a response refuses it. */
typealias RequestCheck = (url: String?) -> WebResourceResponse?

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
