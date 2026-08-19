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
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

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

    /** The proxy currently running, if any. */
    internal var binding: ProxyBinding? = null
        private set

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        container = AppContainer.get(this)
        webView = findViewById(R.id.web_view)
        entry = findViewById(R.id.entry)
        ticketField = findViewById(R.id.ticket_field)
        entryError = findViewById(R.id.entry_error)
        siteData = container.siteData(webView)

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
        // would throw away where the user was. The proxy still has to be
        // started, because the port it bound last time is gone with the process.
        savedInstanceState?.let(webView::restoreState)
        openSelected(loadIndex = savedInstanceState == null)
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
        // Deliberately absent: addJavascriptInterface. There is no bridge from
        // page JavaScript into the app, and adding one would hand an arbitrary
        // peer a foothold in a privileged process.
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest,
            ): Boolean = handleNavigation(request.url?.toString())
        }
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
        if (trimmed.isEmpty()) {
            showEntryError(getString(R.string.error_bad_ticket))
            return
        }
        endpoints = container.store.update {
            it.add(Endpoint(trimmed, Endpoints.defaultName(trimmed)))
        }
        openSelected()
    }

    /** Switch to a saved endpoint. */
    internal fun selectEndpoint(index: Int) {
        endpoints = container.store.update { it.select(index) }
        openSelected()
    }

    /** Forget a saved endpoint, and the session that went with it. */
    internal fun removeEndpoint(index: Int) {
        // Only reachable for the endpoint on screen, which is the one whose
        // origin we know: the label comes from the running proxy. Removing a
        // different one would leave its cookies behind, which is why the menu
        // only ever offers the selected one.
        if (index == endpoints.selectedIndex) {
            binding?.let { siteData.clear(Origins.origin(it.label, it.port)) }
        }
        endpoints = container.store.update { it.remove(index) }
        openSelected()
    }

    /**
     * Point the browser at the selected endpoint.
     *
     * Nothing is cleared here. Each endpoint has its own `<label>.localhost`
     * origin, so the browser keeps their cookies apart on its own, and a session
     * is expected to survive both switching away and closing the app — the
     * clearing that used to happen here ran on every launch too, which is what
     * made a relaunch ask for the password again.
     */
    private fun openSelected(loadIndex: Boolean = true) {
        container.proxy.stop()
        binding = null

        val endpoint = endpoints.selected
        if (endpoint == null) {
            showEntry()
            return
        }

        when (val result = container.proxy.start(endpoint.ticket)) {
            is ProxyResult.Started -> {
                binding = result.binding
                showWebView()
                if (loadIndex) {
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

    private fun showEntry() {
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
        entry.visibility = android.view.View.GONE
        webView.visibility = android.view.View.VISIBLE
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(Menu.NONE, MENU_RELOAD, 0, R.string.menu_reload)
        menu.add(Menu.NONE, MENU_ADD, 1, R.string.menu_add)
        menu.add(Menu.NONE, MENU_SWITCH, 2, R.string.menu_switch)
        menu.add(Menu.NONE, MENU_REMOVE, 3, R.string.menu_remove)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(MENU_RELOAD)?.isEnabled = binding != null
        menu.findItem(MENU_SWITCH)?.isEnabled = endpoints.all.size > 1
        menu.findItem(MENU_REMOVE)?.isEnabled = endpoints.selected != null
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        MENU_RELOAD -> {
            webView.reload()
            true
        }
        MENU_ADD -> {
            showEntry()
            true
        }
        MENU_SWITCH -> {
            showSwitcher()
            true
        }
        MENU_REMOVE -> {
            removeEndpoint(endpoints.selectedIndex)
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    private fun showSwitcher() {
        val names = endpoints.all.map { it.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.menu_switch)
            .setItems(names) { _, index -> selectEndpoint(index) }
            .show()
    }

    override fun onBackPressed() {
        if (webView.visibility == android.view.View.VISIBLE && webView.canGoBack()) {
            webView.goBack()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
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
        // Not in onPause: the proxy has to survive the screen going off, or
        // coming back would need a fresh dial for every connection.
        if (isFinishing) container.proxy.stop()
        super.onDestroy()
    }

    private companion object {
        const val MENU_RELOAD = 1
        const val MENU_ADD = 2
        const val MENU_SWITCH = 3
        const val MENU_REMOVE = 4
    }
}
