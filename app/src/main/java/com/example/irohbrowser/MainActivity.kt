package com.example.irohbrowser

import android.app.ActivityManager
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.util.Log
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
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * One endpoint's card (see [EndpointCards]): the whole app, for that endpoint.
 *
 * It owns a WebView pointed at the endpoint's loopback origin, and the small
 * amount of state that says which endpoint that is. Everything else — cookies,
 * caching, redirects, forms, relative URLs — is the browser's, by design.
 * Other endpoints have cards of their own, beside this one, each with its
 * page and proxy.
 */
class MainActivity : AppCompatActivity() {

    /**
     * The endpoint this card is for, by label: fixed by the intent that opened
     * it, for the card's life. Null for the entry card. Its endpoint may be
     * gone -- removed, here or in another card -- and come back.
     */
    private val label: String? by lazy { EndpointCards.labelOf(intent) }

    private lateinit var container: AppContainer
    private lateinit var webView: WebView
    private lateinit var entry: LinearLayout
    private lateinit var ticketField: EditText
    private lateinit var entryError: TextView
    private lateinit var siteData: SiteData

    /**
     * This card's proxy, while it runs.
     *
     * Volatile because the WebView checks every request against it from its
     * own thread (see [pageCheck]).
     */
    @Volatile
    internal var binding: ProxyBinding? = null
        private set

    /**
     * Set when the endpoint's front page is being loaded after a start: once
     * it has loaded, the history before it is dropped. That history leads to
     * origins no proxy of this card serves any more -- this endpoint on a port
     * it no longer holds, say -- where back would only find an error, or
     * something else listening.
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
    private lateinit var folderChooser: ActivityResultLauncher<Uri?>

    private lateinit var passkeys: PasskeyBridge
    private lateinit var downloads: PageDownloads
    private lateinit var settings: ScrollView
    private lateinit var settingsScreen: SettingsScreen

    /**
     * Take the page bridges away from the origin they were offered to. Empty
     * when nothing is offered: no endpoint running, or a WebView without the
     * features a bridge needs.
     */
    private var bridgeWithdrawals: List<() -> Unit> = emptyList()

    /** The WebView's own renderer priority, to go back to once nothing plays. */
    private var rendererDefault: Pair<Int, Boolean> = WebView.RENDERER_PRIORITY_IMPORTANT to true

    /** The check on the page's requests: this card's proxy, and nothing else. */
    private val pageCheck: RequestCheck = requestCheck { listOfNotNull(binding) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        container = AppContainer.get(this)
        // The launcher's way in: no card of its own, but the last endpoint's.
        if (EndpointCards.isLauncher(intent)) {
            startActivity(EndpointCards.lastUsed(this, container.store.load(), container.proxy::identify))
            finishAndRemoveTask()
            return
        }
        setContentView(R.layout.activity_main)
        keepClearOfSystemBars()

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

        downloads = PageDownloads(container.downloads, container.io, mainExecutor, ::announce)

        fileChooser = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            pendingFileChooser?.onReceiveValue(uris.orEmpty().toTypedArray())
            pendingFileChooser = null
        }
        // A folder, for a page's showDirectoryPicker(): the WebView is handed
        // the folder, and reads the files in it itself.
        folderChooser = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { folder ->
            pendingFileChooser?.onReceiveValue(folder?.let { arrayOf(it) })
            pendingFileChooser = null
        }
        // From Android 16 on, back no longer reaches onBackPressed: it comes
        // through the dispatcher, to this.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (goBack()) return
                // Not ours: what the system does, as if this weren't here.
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
                isEnabled = true
            }
        })

        configureWebView()
        findViewById<Button>(R.id.connect_button).setOnClickListener {
            addEndpoint(ticketField.text.toString())
        }

        endpoints = container.store.load()
        // A restored WebView state already names a page; re-loading the index
        // would throw away where the user was. It is handed to openEndpoint
        // rather than restored here: the proxy has to be running first.
        openEndpoint(restoring = savedInstanceState)
    }

    /**
     * Opened again (EndpointCards): as it was left, unless its endpoint has no
     * page -- it would not start, or was removed and has been added again.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        endpoints = container.store.load()
        if (binding == null && ownIndex() >= 0) openEndpoint()
    }

    override fun onResume() {
        super.onResume()
        // Another card may have renamed this endpoint, or changed it, meanwhile.
        endpoints = container.store.load()
        val index = ownIndex()
        // The card in front is the one the launcher opens next time.
        if (index >= 0 && index != endpoints.selectedIndex) endpoints = container.store.update { it.select(index) }
        endpoints.all.getOrNull(index)?.let { webView.settings.mediaPlaybackRequiresUserGesture = !it.autoplay }
        describeCard()
        if (settings.visibility == android.view.View.VISIBLE) showSettings()
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
        runCatching { rendererDefault = webView.rendererRequestedPriority to webView.rendererPriorityWaivedWhenNotVisible }
        // Deliberately absent: addJavascriptInterface. The ways from page
        // JavaScript into the app are the passkey, clipboard, media session and
        // download bridges (offerBridges), which the WebView restricts to the
        // endpoint's origin and which carry data, not callable methods.
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest,
            ): Boolean = handleNavigation(request.url?.toString(), byUser = request.hasGesture())

            // Every request, before it leaves: subresources and fetches, and
            // also back/forward and restored pages, which never reach
            // shouldOverrideUrlLoading.
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                pageCheck(request.url?.toString())

            // A new page in the main frame: whatever the last one played, or
            // was saving, went with it, even if it never said so.
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                endMedia()
                downloads.cancel()
            }

            override fun onPageFinished(view: WebView, url: String?) {
                val running = binding ?: return
                if (clearHistoryOnceLoaded && Origins.isOwnOrigin(url, running.label, running.port)) {
                    clearHistoryOnceLoaded = false
                    view.clearHistory()
                }
            }
        }
        // A page's service worker fetches through its own client, not the
        // WebViewClient. The route is process-wide, as are the workers, which
        // do not say whose page they are: they may reach every running proxy.
        // It holds nothing of a card, so it stays: with no card open, it
        // refuses everything.
        container.serviceWorkers.route(requestCheck(container.proxy::running))
        // WebRTC's UDP passes beneath both the request check and the proxy
        // override (Confinement), so pages do not get it. Before anything
        // loads: the script reaches only documents that start after it.
        container.pageScripts.install(
            webView,
            assets.open(Confinement.SCRIPT_ASSET).bufferedReader().use { it.readText() },
        )
        // Without this a download link does nothing at all -- no error, no file.
        // A page's own files (blob: URLs) don't come this way: the download
        // bridge takes them (offerBridges).
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
                // Android 17's WebView asks for a folder for a page's
                // showDirectoryPicker() -- only from apps targeting 37, which
                // get the File System Access API. A folder input it takes for
                // one picking files, whatever the app targets.
                if (params?.mode == FileChooserParams.MODE_OPEN_FOLDER) {
                    return runCatching { folderChooser.launch(null) }.isSuccess
                }
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
        // Not ours, or a blob: URL the download bridge didn't catch: say so,
        // rather than nothing happening.
        val target = Downloads.systemUrl(url, current.label, current.port)
            ?: return Toast.makeText(this, R.string.download_failed, Toast.LENGTH_SHORT).show()
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

    /** What the user hears of a file a page saves through the download bridge. */
    private fun announce(event: DownloadEvent) {
        val text = when (event) {
            is DownloadEvent.Started -> R.string.download_saving
            is DownloadEvent.Saved -> R.string.download_saved
            is DownloadEvent.Failed -> R.string.download_not_saved
        }
        Toast.makeText(this, getString(text, event.name), Toast.LENGTH_SHORT).show()
    }

    /**
     * Decide where a navigation goes.
     *
     * Returns true when the WebView should *not* load it, which is
     * `shouldOverrideUrlLoading`'s convention.
     */
    private fun handleNavigation(url: String?, byUser: Boolean): Boolean {
        val current = binding ?: return true
        return when (Origins.externalDestination(url, current.label, current.port, byUser)) {
            Origins.Destination.Keep -> false
            Origins.Destination.OpenExternally -> {
                openExternally(url)
                true
            }
            Origins.Destination.Refuse -> {
                Log.i(LOG_TAG, "refused a navigation to ${forLog(url)}")
                true
            }
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

    /**
     * Save a ticket, and open its endpoint: in its own card, which this one
     * gives way to if it has nothing on it.
     */
    internal fun addEndpoint(ticket: String) {
        val trimmed = ticket.trim()
        // Checked before saving: a string that names no endpoint would only
        // sit in the list as an entry that can never open.
        val identity = trimmed.takeIf { it.isNotEmpty() }?.let(container.proxy::identify)
        if (identity == null) {
            showEntryError(getString(R.string.error_bad_ticket))
            return
        }
        endpoints = container.store.update { it.add(Endpoint(trimmed)) }
        if (identity.label == label) openEndpoint() else openCard(identity.label)
    }

    /**
     * Open saved endpoint [index]: this card's is shown, or started again if it
     * has no page; another's card is brought forward, or opened.
     */
    internal fun openEndpointAt(index: Int) {
        val endpoint = endpoints.all.getOrNull(index) ?: return
        val target = container.proxy.identify(endpoint.ticket)?.label
            ?: return showEntryError(getString(R.string.error_bad_ticket))
        when {
            target != label -> openCard(target)
            // Already there: only shown again, as reconnecting would reload it.
            binding != null -> showWebView()
            else -> openEndpoint()
        }
    }

    /**
     * Bring [target]'s card forward, or open it. This card stays behind as it
     * was, its page back on screen for when it is next shown -- unless there is
     * nothing on it, no endpoint or one that would not start, when it goes.
     */
    private fun openCard(target: String) {
        startActivity(EndpointCards.intent(this, target))
        if (binding == null) finishAndRemoveTask() else showWebView()
    }

    /**
     * Forget a saved endpoint, and the session that went with it.
     *
     * Any endpoint, not only this card's. Removing this card's leaves the card
     * with none, until one is opened from it; removing another's closes that
     * one's card.
     */
    internal fun removeEndpoint(index: Int) {
        val endpoint = endpoints.all.getOrNull(index) ?: return
        val identity = container.proxy.identify(endpoint.ticket)
        // Before its proxy stops, which is the authority on its port.
        val origin = identity?.let(::originOf)
        val here = identity != null && identity.label == label
        when {
            identity == null -> Unit
            here -> {
                closeEndpoint()
                // The page would only go on making requests nothing answers.
                webView.loadUrl("about:blank")
            }
            else -> container.cardTasks.close(this, identity.label)
        }
        origin?.let(siteData::clear)
        endpoints = container.store.update { it.remove(index) }
        if (here) {
            describeCard()
            if (endpoints.isEmpty) showEntry()
        }
    }

    /**
     * Where [identity]'s endpoint is browsed: its running proxy's origin, if
     * it is open in a card -- the proxy may have fallen back from its preferred
     * port -- and the origin its ticket names otherwise.
     */
    private fun originOf(identity: EndpointIdentity): String {
        val running = container.proxy.running().find { it.label == identity.label }
        return running?.let { Origins.origin(it.label, it.port) } ?: Origins.origin(identity.label, identity.preferredPort)
    }

    /** Where this card's endpoint is in [endpoints]; -1 for the entry card, and when its endpoint is gone. */
    private fun ownIndex(): Int {
        val own = label ?: return -1
        return endpoints.all.indexOfFirst { container.proxy.identify(it.ticket)?.label == own }
    }

    /** What the card is called, in the recent apps and its app bar: its endpoint's name. */
    private fun cardName(): String =
        endpoints.all.getOrNull(ownIndex())?.displayName(label) ?: getString(R.string.app_name)

    private fun describeCard() {
        val name = cardName()
        setTaskDescription(ActivityManager.TaskDescription.Builder().setLabel(name).build())
        if (settings.visibility != android.view.View.VISIBLE) supportActionBar?.title = name
    }

    /**
     * Point the browser at this card's endpoint, its proxy started anew.
     *
     * Nothing is cleared here. Each endpoint has its own `<label>.localhost`
     * origin, so the browser keeps their cookies apart on its own, and a session
     * is expected to survive both closing a card and closing the app — the
     * clearing that used to happen here ran on every launch too, which is what
     * made a relaunch ask for the password again.
     *
     * [restoring] is the saved state of a WebView being brought back after the
     * process was killed. It is restored only once the proxy is running and
     * the bridges' scripts installed: the WebView starts loading the restored
     * page at once, on its own thread, and that request is checked against
     * [binding] -- restored earlier, it was refused. The restored page is then
     * kept only when it is on the origin this proxy now serves: if the proxy
     * had to fall back from the port the page was saved on -- quite possibly
     * because something else is holding that port -- the page and its history
     * would lead there.
     */
    private fun openEndpoint(restoring: Bundle? = null) {
        // Stopped first even with no page: an earlier activity of this card
        // may have left it running, holding the port it should get again.
        closeEndpoint()

        val endpoint = endpoints.all.getOrNull(ownIndex())
        describeCard()
        if (endpoint == null) {
            showEntry()
            return
        }

        when (val result = container.proxy.start(endpoint.ticket)) {
            is ProxyResult.Started -> {
                binding = result.binding
                webView.settings.mediaPlaybackRequiresUserGesture = !endpoint.autoplay
                // Before the load: the scripts are injected into documents
                // that start after this, and the first page should have them.
                offerBridges(result.binding)
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

    /** This card's page loses its proxy, and the bridges with it. */
    private fun closeEndpoint() {
        label?.let(container.proxy::stop)
        binding = null
        withdrawBridges()
    }

    /**
     * Give this endpoint's pages, and only them, a way to reach its passkeys,
     * to copy text marked sensitive, to show what they play, and to save the
     * files they hold.
     *
     * The origin is fixed here, from the proxy that was just started: the
     * origin a ceremony signs for is never one the page supplied. The name is
     * read per request, so a rename shows in the very next prompt.
     */
    private fun offerBridges(running: ProxyBinding) {
        val origin = Origins.origin(running.label, running.port)
        bridgeWithdrawals = listOfNotNull(
            container.passkeys.installer.install(webView, origin) { message, sourceOrigin, reply ->
                val name = cardName()
                passkeys.receive(message, sourceOrigin, PasskeySite(running.label, running.port, name), reply)
            },
            container.clipboardBridge.install(webView, origin) { message, sourceOrigin, reply ->
                // Messages arrive on the main thread, so the view can be asked.
                val onScreen = webView.isShown && webView.hasWindowFocus()
                ClipboardBridge.answer(message, sourceOrigin, running, onScreen) { copySensitive(this, it) }
                    ?.let(reply)
            },
            container.mediaBridge.install(webView, origin) { message, sourceOrigin, reply ->
                when (val report = MediaSessionBridge.parse(message, sourceOrigin, running)) {
                    null -> Unit
                    MediaReport.Nothing -> endMedia()
                    is MediaReport.Current -> {
                        container.media.show(running.label, report.media) { action, seekToMs -> reply(MediaSessionBridge.command(action, seekToMs)) }
                        keepRenderer()
                    }
                }
            },
            container.downloadBridge.install(
                webView,
                origin,
                receiveBytes = { bytes, sourceOrigin, _ -> downloads.receive(bytes, sourceOrigin, running) },
            ) { message, sourceOrigin, reply -> downloads.receive(message, sourceOrigin, running, reply) },
        )
    }

    /**
     * Withdraw the bridges, and any prompt the departing endpoint's page had
     * raised, what it played, and what it was saving.
     */
    private fun withdrawBridges() {
        passkeys.cancel()
        downloads.cancel()
        bridgeWithdrawals.forEach { it() }
        bridgeWithdrawals = emptyList()
        endMedia()
    }

    private fun endMedia() {
        label?.let(container.media::end)
        keepRenderer()
    }

    /** Whether the system shows what this card's page plays: the media session is the app's, and goes to one page at a time. */
    private val playing: Boolean
        get() = label != null && container.media.owner == label

    /**
     * While something plays, the page's renderer stays as important as the
     * app even when it is not on screen; by default Android may reclaim a
     * hidden one, and the music with it. The foreground service keeps the app.
     * Best effort: a WebView that cannot do this still plays, in the
     * foreground at least.
     */
    private fun keepRenderer() {
        val (priority, waived) = if (playing) WebView.RENDERER_PRIORITY_IMPORTANT to false else rendererDefault
        runCatching { webView.setRendererPriorityPolicy(priority, waived) }
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
                running = container.proxy.running(),
                here = label,
                passkeys = container.passkeys.store.load(),
                storage = { container.passkeys.vault.storage(it.alias) },
            ),
        )
        settings.visibility = android.view.View.VISIBLE
        entry.visibility = android.view.View.GONE
        webView.visibility = android.view.View.GONE
        showingSettings(true)
    }

    /** Back to whatever the list was opened over: the page, or the ticket field when the card has none. */
    private fun leaveSettings() {
        if (binding != null) showWebView() else showEntry()
    }

    /** The app bar's half of the settings screen: a title saying where you are, and a way out. */
    private fun showingSettings(shown: Boolean) {
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(shown)
            title = if (shown) getString(R.string.menu_settings) else cardName()
        }
    }

    private val settingsActions = object : SettingsActions {
        override fun open(index: Int) = openEndpointAt(index)

        override fun rename(index: Int, name: String) {
            endpoints = container.store.update { it.rename(index, name) }
            describeCard()
            showSettings()
        }

        override fun remove(index: Int) {
            removeEndpoint(index)
            // Stay here while there is still a list to show.
            if (endpoints.all.isNotEmpty()) showSettings()
        }

        override fun setAutoplay(index: Int, on: Boolean) {
            endpoints = container.store.update { it.setAutoplay(index, on) }
            // Another card's page has it the next time that card is shown.
            if (index == ownIndex()) webView.settings.mediaPlaybackRequiresUserGesture = !on
            showSettings()
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

    /** Back, where it means something here. False leaves it to the system. */
    private fun goBack(): Boolean {
        when {
            settings.visibility == android.view.View.VISIBLE -> leaveSettings()
            // The ticket field, reached from "Add endpoint" while one is open.
            entry.visibility == android.view.View.VISIBLE && binding != null -> showWebView()
            webView.visibility == android.view.View.VISIBLE && webView.canGoBack() -> webView.goBack()
            // Leaving would close the page, and stop what it plays: go to the
            // background instead, as a music app does.
            playing -> moveTaskToBack(true)
            else -> return false
        }
        return true
    }

    /**
     * From Android 15 on, the window is drawn edge to edge: under the status
     * and navigation bars, a display cutout, and the keyboard. The views are
     * kept clear of all of them -- the keyboard's part is what used to resize
     * the page for it.
     */
    private fun keepClearOfSystemBars() {
        val covered = WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime()
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { view, insets ->
            val clear = insets.getInsets(covered)
            view.setPadding(clear.left, clear.top, clear.right, clear.bottom)
            WindowInsetsCompat.CONSUMED
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
        // The launcher's way in, gone before it had anything.
        if (!::webView.isInitialized) return super.onDestroy()
        // A page waiting on a chooser that will never answer would be stuck.
        pendingFileChooser?.onReceiveValue(null)
        pendingFileChooser = null
        withdrawBridges()
        // Not in onPause: the proxy has to survive the screen going off, or
        // coming back would need a fresh dial for every connection. Finishing
        // is the card closing -- by back, or from the recent apps.
        if (isFinishing) label?.let(container.proxy::stop)
        super.onDestroy()
    }

    private companion object {
        const val MENU_RELOAD = 1
        const val MENU_SETTINGS = 2
    }
}
