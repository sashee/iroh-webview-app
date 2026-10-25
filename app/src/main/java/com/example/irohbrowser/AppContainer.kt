package com.example.irohbrowser

import android.content.Context
import android.webkit.WebView

/**
 * The app's dependencies, in one place that a test can replace.
 *
 * Same shape as sms-forwarder's container: production builds it from the real
 * implementations, tests install their own before the activity starts. It exists
 * because the alternative — reaching for `NativeProxy` from the activity — would
 * make every activity test load a native library built for a phone.
 */
class AppContainer(
    val store: EndpointStore,
    val proxy: ProxyController,
    val passkeys: PasskeyPlatform,
    /** The bridge `assets/clipboard.js` copies through (see [ClipboardBridge]). */
    val clipboardBridge: BridgeInstaller,
    val serviceWorkers: ServiceWorkerRequests,
    val pageScripts: PageScripts,
    /** The bridge `assets/media-session.js` relays the page's Media Session through (see [MediaSessionBridge]). */
    val mediaBridge: BridgeInstaller,
    /** What shows the system what a page plays. Process-wide, like the service holding its notification. */
    val media: MediaPlayback,
    /**
     * How to reach the browser's stored site data. A factory rather than a
     * value because the real one needs the activity's WebView, which does not
     * exist when the container is built.
     */
    val siteData: (WebView) -> SiteData = ::WebViewSiteData,
) {
    companion object {
        @Volatile
        private var installed: AppContainer? = null

        fun get(context: Context): AppContainer =
            installed ?: synchronized(this) {
                installed ?: production(context).also { installed = it }
            }

        /** Replace the container. For tests, and for `Application.onCreate`. */
        fun install(container: AppContainer?) {
            synchronized(this) { installed = container }
        }

        private fun production(context: Context): AppContainer =
            AppContainer(
                store = EndpointStore.from(context),
                proxy = NativeProxy,
                passkeys = PasskeyPlatform(
                    installer = WebViewBridgeInstaller(PasskeyBridge.NAME, PasskeyBridge.SCRIPT_ASSET),
                    store = PasskeyStore.from(context),
                    vault = AndroidKeyVault,
                    ui = ::BiometricPasskeyUi,
                ),
                clipboardBridge = WebViewBridgeInstaller(ClipboardBridge.NAME, ClipboardBridge.SCRIPT_ASSET),
                serviceWorkers = PlatformServiceWorkerRequests,
                pageScripts = WebViewPageScripts,
                mediaBridge = WebViewBridgeInstaller(MediaSessionBridge.NAME, MediaSessionBridge.SCRIPT_ASSET),
                media = AndroidMediaPlayback(context),
            )
    }
}
