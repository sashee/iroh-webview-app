package com.example.irohbrowser

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.view.Menu
import android.view.MenuItem
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.io.ByteArrayInputStream

/**
 * The whole app.
 *
 * It owns a WebView pointed at a loopback origin, and the small amount of state
 * that says which endpoint that origin belongs to. Everything else — cookies,
 * caching, redirects, forms, relative URLs — is the browser's, by design.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var container: AppContainer
    private lateinit var webView: WebView
    private lateinit var entry: LinearLayout
    private lateinit var ticketField: EditText
    private lateinit var entryError: TextView
    private lateinit var siteData: SiteData

    /**
     * The proxy currently running, if any.
     *
     * Volatile because the WebView checks every request against it from its
     * own thread (see [refusal]).
     */
    @Volatile
    internal var binding: ProxyBinding? = null
        private set

    /**
     * Set when an endpoint's front page is being loaded after a switch or a
     * port change: once it has loaded, the history before it is dropped. That
     * history leads to origins no proxy is serving any more -- another
     * endpoint, or this one on a port it no longer holds -- where back would
     * only find an error, or something else listening.
     */
    private var clearHistoryOnceLoaded = false

    /** The saved endpoints, as last read or written. */
    internal var endpoints: Endpoints = Endpoints()
        private set

    /**
     * Where to deliver the result of a file chooser the page asked for.
     *
     * Held across the trip to another activity. A pending callback that is never
     * answered leaves the page's file input wedged, so every path out of here
     * has to answer it -- with null if need be.
     */
    private var pendingFileChooser: ValueCallback<Array<Uri>>? = null

    private lateinit var fileChooser: ActivityResultLauncher<Array<String>>

    private lateinit var passkeys: PasskeyBridge
    private lateinit var settings: ScrollView
    private lateinit var settingsScreen: SettingsScreen

    /**
     * Takes the passkey bridge away from the origin it was offered to. Null
     * when nothing is offered: no endpoint running, or a WebView without the
     * features the bridge needs.
     */
    private var withdrawPasskeyBridge: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        container = AppContainer.get(this)
        webView = findViewById(R.id.web_view)
        entry = findViewById(R.id.entry)
        ticketField = findViewById(R.id.ticket_field)
        entryError = findViewById(R.id.entry_error)
        siteData = container.siteData(webView)
        settings = findViewById(R.id.settings)
        settingsScreen = SettingsScreen(findViewById(R.id.settings_content), settingsActions)
        passkeys = PasskeyBridge(
            PasskeyAuthenticator(
                store = container.passkeys.store,
                vault = container.passkeys.vault,
                ui = container.passkeys.ui(this),
                random = container.passkeys.random,
                clock = container.passkeys.clock,
            ),
        )

        fileChooser = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            pendingFileChooser?.onReceiveValue(uris.orEmpty().toTypedArray())
            pendingFileChooser = null
        }

        configureWebView()
        findViewById<Button>(R.id.connect_button).setOnClickListener {
            addEndpoint(ticketField.text.toString())
        }

        endpoints = container.store.load()
        // A restored WebView state already names a page; re-loading the index
        // would throw away where the user was. It is handed to openSelected
        // rather than restored here: the proxy has to be running first.
        openSelected(restoring = savedInstanceState)
    }

    private fun configureWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            // The page comes from an arbitrary peer. Nothing it can do should
            // reach the device's own storage.
            allowFileAccess = false
            allowContentAccess = false
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = false
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = false
            // The proxy origin is http, and everything it loads is same-origin.
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW

            // Pinch-to-zoom. `setSupportZoom` is on by default but does nothing
            // on its own: the gesture is handled by the built-in zoom support,
            // which defaults to *off*. The on-screen +/- buttons that come with
            // it are turned off separately -- they overlay the page and no
            // phone browser has shown them for years.
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false

            // Lay pages out the way a browser does: honour the page's viewport
            // if it declares one, and start zoomed out far enough to see a wide
            // page whole. Without the pair, a page written for a desktop is
            // squeezed into the phone's width instead of being zoomable.
            useWideViewPort = true
            loadWithOverviewMode = true
        }
        // Deliberately absent: addJavascriptInterface. The one way from page
        // JavaScript into the app is the passkey bridge (offerPasskeys), which
        // the WebView restricts to the endpoint's origin and which carries
        // data, not callable methods.
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest,
            ): Boolean = handleNavigation(request.url?.toString())

            // Every request, before it leaves: subresources and fetches, and
            // also back/forward and restored pages, which never reach
            // shouldOverrideUrlLoading.
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                refusal(request.url?.toString())

            override fun onPageFinished(view: WebView, url: String?) {
                val running = binding ?: return
                if (clearHistoryOnceLoaded && Origins.isOwnOrigin(url, running.label, running.port)) {
                    clearHistoryOnceLoaded = false
                    view.clearHistory()
                }
            }
        }
        // A page's service worker fetches through its own client, not the
        // WebViewClient. The route is process-wide; onDestroy removes it.
        container.serviceWorkers.route(::refusal)
        // Without this a download link does nothing at all -- no error, no file.
        webView.setDownloadListener { url, _, contentDisposition, mimeType, _ ->
            startDownload(url, contentDisposition, mimeType)
        }
        // Without this an <input type="file"> does nothing when tapped.
        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                view: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                params: FileChooserParams?,
            ): Boolean {
                // A second request supersedes the first, which must still be
                // answered or its input stays disabled forever.
                pendingFileChooser?.onReceiveValue(null)
                pendingFileChooser = filePathCallback
                val types = params?.acceptTypes
                    ?.filter { it.isNotBlank() }
                    ?.toTypedArray()
                    ?.takeIf { it.isNotEmpty() }
                    ?: arrayOf("*/*")
                return runCatching { fileChooser.launch(types) }.isSuccess
            }
        }
    }

    /**
     * Save a file the page offered.
     *
     * Handed to the system downloader rather than fetched here, so it survives
     * the app being backgrounded and lands in Downloads like any other file. The
     * cookies have to be copied across by hand: the request is made by another
     * process, which has no access to this app's cookie jar.
     */
    private fun startDownload(url: String?, contentDisposition: String?, mimeType: String?) {
        val current = binding ?: return
        val target = Downloads.systemUrl(url, current.label, current.port) ?: return
        val name = Downloads.fileName(contentDisposition, url)

        val request = DownloadManager.Request(Uri.parse(target)).apply {
            CookieManager.getInstance().getCookie(url)?.let { addRequestHeader("Cookie", it) }
            mimeType?.takeIf { it.isNotBlank() }?.let { setMimeType(it) }
            setTitle(name)
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
        }
        val queued = runCatching {
            (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
        }
        Toast.makeText(
            this,
            if (queued.isSuccess) getString(R.string.download_started, name)
            else getString(R.string.download_failed),
            Toast.LENGTH_SHORT,
        ).show()
    }

    /**
     * Decide where a navigation goes.
     *
     * Returns true when the WebView should *not* load it, which is
     * `shouldOverrideUrlLoading`'s convention.
     */
    internal fun handleNavigation(url: String?): Boolean {
        val current = binding ?: return true
        return when (Origins.externalDestination(url, current.label, current.port)) {
            Origins.Destination.Keep -> false
            Origins.Destination.OpenExternally -> {
                openExternally(url)
                true
            }
            Origins.Destination.Refuse -> true
        }
    }

    private fun openExternally(url: String?) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url ?: return)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            // Without this an http(s) link could resolve back to this app if it
            // ever declares a matching filter, which would defeat the point.
            addCategory(Intent.CATEGORY_BROWSABLE)
        }
        runCatching { startActivity(intent) }
    }

    /** Save a ticket and switch to it. */
    internal fun addEndpoint(ticket: String) {
        val trimmed = ticket.trim()
        // Checked before saving: a string that names no endpoint would only
        // sit in the list as an entry that can never open.
        if (trimmed.isEmpty() || container.proxy.identify(trimmed) == null) {
            showEntryError(getString(R.string.error_bad_ticket))
            return
        }
        endpoints = container.store.update { it.add(Endpoint(trimmed)) }
        openSelected()
    }

    /** Switch to a saved endpoint. */
    internal fun selectEndpoint(index: Int) {
        endpoints = container.store.update { it.select(index) }
        openSelected()
    }

    /**
     * Forget a saved endpoint, and the session that went with it.
     *
     * Any endpoint, not only the one on screen. Only removing the open one
     * changes what is on screen; removing another leaves its proxy, and the
     * page, alone.
     */
    internal fun removeEndpoint(index: Int) {
        if (index !in endpoints.all.indices) return
        val open = index == endpoints.selectedIndex
        originOf(index)?.let(siteData::clear)
        endpoints = container.store.update { it.remove(index) }
        if (open) openSelected()
    }

    /**
     * Where endpoint [index] is browsed: the running proxy's origin if it is the
     * open one -- the proxy may have fallen back from its preferred port --
     * and the origin its ticket names otherwise.
     */
    private fun originOf(index: Int): String? {
        val running = binding?.takeIf { index == endpoints.selectedIndex }
        if (running != null) return Origins.origin(running.label, running.port)
        val identity = container.proxy.identify(endpoints.all[index].ticket) ?: return null
        return Origins.origin(identity.label, identity.preferredPort)
    }

    /**
     * Null to let a request to [url] through; a refusal when it is loopback
     * but not the running proxy's origin -- where the endpoint's cookies would
     * go to whatever else is listening. See [Origins.mayRequest].
     */
    private fun refusal(url: String?): WebResourceResponse? =
        if (Origins.mayRequest(url, binding)) {
            null
        } else {
            WebResourceResponse(
                "text/plain",
                "utf-8",
                403,
                "Forbidden",
                mapOf("Cache-Control" to "no-store"),
                ByteArrayInputStream("Refused: not the origin of the endpoint on screen.".toByteArray()),
            )
        }

    /**
     * Point the browser at the selected endpoint.
     *
     * Nothing is cleared here. Each endpoint has its own `<label>.localhost`
     * origin, so the browser keeps their cookies apart on its own, and a session
     * is expected to survive both switching away and closing the app — the
     * clearing that used to happen here ran on every launch too, which is what
     * made a relaunch ask for the password again.
     *
     * [restoring] is the saved state of a WebView being brought back after the
     * process was killed. It is restored only once the proxy is running and
     * the passkey script installed: the WebView starts loading the restored
     * page at once, on its own thread, and that request is checked against
     * [binding] -- restored earlier, it was refused. The restored page is then
     * kept only when it is on the origin this proxy now serves: if the proxy
     * had to fall back from the port the page was saved on -- quite possibly
     * because something else is holding that port -- the page and its history
     * would lead there.
     */
    private fun openSelected(restoring: Bundle? = null) {
        container.proxy.stop()
        binding = null
        withdrawPasskeys()

        val endpoint = endpoints.selected
        if (endpoint == null) {
            showEntry()
            return
        }

        when (val result = container.proxy.start(endpoint.ticket)) {
            is ProxyResult.Started -> {
                binding = result.binding
                // Before the load: the script is injected into documents that
                // start after this, and the first page should have it.
                offerPasskeys(result.binding)
                showWebView()
                val restoredPage = restoring?.let(webView::restoreState)?.currentItem?.url
                val keepRestored = restoredPage != null &&
                    Origins.isOwnOrigin(restoredPage, result.binding.label, result.binding.port)
                if (!keepRestored) {
                    clearHistoryOnceLoaded = true
                    webView.loadUrl(Origins.url(result.binding.label, result.binding.port))
                }
            }
            is ProxyResult.Failed -> {
                // The endpoint stays saved: a peer that is unreachable now is
                // not a peer that was typed wrong, and dropping it would make
                // the user paste it again.
                showEntryError(
                    when (result.error) {
                        ProxyError.BadTicket -> getString(R.string.error_bad_ticket)
                        ProxyError.StartFailed -> getString(R.string.error_start_failed)
                    },
                )
            }
        }
    }

    /**
     * Give this endpoint's pages, and only them, a way to reach its passkeys.
     *
     * The origin is fixed here, from the proxy that was just started: the
     * origin a ceremony signs for is never one the page supplied. The name is
     * read per request, so a rename shows in the very next prompt.
     */
    private fun offerPasskeys(running: ProxyBinding) {
        val origin = Origins.origin(running.label, running.port)
        withdrawPasskeyBridge = container.passkeys.installer.install(webView, origin) { message, sourceOrigin, reply ->
            val name = endpoints.selected?.displayName(running.label) ?: running.label
            passkeys.receive(message, sourceOrigin, PasskeySite(running.label, running.port, name), reply)
        }
    }

    /** Withdraw the bridge, and any prompt the departing endpoint's page had raised. */
    private fun withdrawPasskeys() {
        passkeys.cancel()
        withdrawPasskeyBridge?.invoke()
        withdrawPasskeyBridge = null
    }

    private fun showEntry() {
        showingSettings(false)
        settings.visibility = android.view.View.GONE
        entry.visibility = android.view.View.VISIBLE
        webView.visibility = android.view.View.GONE
        entryError.visibility = android.view.View.GONE
        ticketField.setText("")
    }

    private fun showEntryError(message: String) {
        showEntry()
        entryError.text = message
        entryError.visibility = android.view.View.VISIBLE
    }

    private fun showWebView() {
        showingSettings(false)
        settings.visibility = android.view.View.GONE
        entry.visibility = android.view.View.GONE
        webView.visibility = android.view.View.VISIBLE
    }

    /**
     * Every endpoint and its passkeys, drawn from the saved state each time
     * rather than kept up to date: the screen holds nothing that could go stale.
     */
    internal fun showSettings() {
        settingsScreen.render(
            Settings.model(
                endpoints = endpoints,
                identities = endpoints.all.map { container.proxy.identify(it.ticket) },
                running = binding,
                passkeys = container.passkeys.store.load(),
                storage = { container.passkeys.vault.storage(it.alias) },
            ),
        )
        settings.visibility = android.view.View.VISIBLE
        entry.visibility = android.view.View.GONE
        webView.visibility = android.view.View.GONE
        showingSettings(true)
    }

    /** Back to whatever the list was opened over: the page, or the ticket field when nothing runs. */
    private fun leaveSettings() {
        if (binding != null) showWebView() else showEntry()
    }

    /** The app bar's half of the settings screen: a title saying where you are, and a way out. */
    private fun showingSettings(shown: Boolean) {
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(shown)
            title = getString(if (shown) R.string.menu_settings else R.string.app_name)
        }
    }

    private val settingsActions = object : SettingsActions {
        override fun open(index: Int) {
            // The one already open is only shown again: reconnecting would
            // reload a page that is already there.
            if (index == endpoints.selectedIndex && binding != null) showWebView() else selectEndpoint(index)
        }

        override fun rename(index: Int, name: String) {
            endpoints = container.store.update { it.rename(index, name) }
            showSettings()
        }

        override fun remove(index: Int) {
            removeEndpoint(index)
            // Removing the open endpoint opens another one behind this screen;
            // stay here while there is still a list to show.
            if (endpoints.all.isNotEmpty()) showSettings()
        }

        override fun deletePasskey(credentialId: String) {
            container.passkeys.store.forget(credentialId, container.passkeys.vault)
            showSettings()
        }

        override fun addEndpoint() = showEntry()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(Menu.NONE, MENU_RELOAD, 0, R.string.menu_reload)
        menu.add(Menu.NONE, MENU_SETTINGS, 1, R.string.menu_settings)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(MENU_RELOAD)?.isEnabled = binding != null
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        MENU_RELOAD -> {
            webView.reload()
            true
        }
        MENU_SETTINGS -> {
            showSettings()
            true
        }
        android.R.id.home -> {
            leaveSettings()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    override fun onBackPressed() {
        when {
            settings.visibility == android.view.View.VISIBLE -> leaveSettings()
            // The ticket field, reached from "Add endpoint" while one is open.
            entry.visibility == android.view.View.VISIBLE && binding != null -> showWebView()
            webView.visibility == android.view.View.VISIBLE && webView.canGoBack() -> webView.goBack()
            else -> {
                @Suppress("DEPRECATION")
                super.onBackPressed()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        // Persistent cookies are written lazily; a kill from the recents screen
        // never gets another chance.
        siteData.flush()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onDestroy() {
        // A page waiting on a chooser that will never answer would be stuck.
        pendingFileChooser?.onReceiveValue(null)
        pendingFileChooser = null
        withdrawPasskeys()
        container.serviceWorkers.route(null)
        // Not in onPause: the proxy has to survive the screen going off, or
        // coming back would need a fresh dial for every connection.
        if (isFinishing) container.proxy.stop()
        super.onDestroy()
    }

    private companion object {
        const val MENU_RELOAD = 1
        const val MENU_SETTINGS = 2
    }
}
