package com.example.irohbrowser

import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView

/**
 * Everything the browser keeps for the sites it has visited.
 *
 * An interface because the rules about *when* to forget are ours and worth
 * asserting, while `CookieManager`'s own behaviour is the framework's and not.
 *
 * There is deliberately no "forget everything": endpoints are kept apart by
 * having distinct `<label>.localhost` origins, so the browser already stores
 * them separately, and a blanket clear would take one endpoint's session away
 * because another one was touched.
 */
interface SiteData {
    /**
     * Write pending cookies to disk.
     *
     * Persistent cookies are written lazily, and an app killed from the recents
     * screen never gets to finish. Called from `onPause`, which is the last
     * callback guaranteed to run.
     */
    fun flush()

    /**
     * Forget what is stored for one origin, and only that origin.
     *
     * Called when an endpoint is removed — "forget this server" should take its
     * session with it, while leaving every other endpoint logged in.
     */
    fun clear(origin: String)
}

/** The real one. */
class WebViewSiteData(private val webView: WebView) : SiteData {

    override fun flush() {
        CookieManager.getInstance().flush()
    }

    override fun clear(origin: String) {
        val cookies = CookieManager.getInstance()
        // There is no "delete the cookies for this host" call, so each one is
        // expired by name instead. `getCookie` returns the same `name=value`
        // pairs a request would carry, which is exactly the set to expire.
        cookies.getCookie(origin)
            ?.split(';')
            ?.map { it.substringBefore('=').trim() }
            ?.filter { it.isNotEmpty() }
            ?.forEach { name -> cookies.setCookie(origin, "$name=; Path=/; Max-Age=0") }
        cookies.flush()

        WebStorage.getInstance().deleteOrigin(origin)
        webView.clearFormData()
        // Not per-origin -- there is no such API -- so this costs every origin's
        // cached assets. Removal is rare and a cold cache is only slow, not
        // wrong.
        webView.clearCache(true)
    }
}
