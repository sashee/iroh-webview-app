package com.example.irohbrowser

import android.webkit.WebSettings
import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import com.example.irohbrowser.testing.TestHarness
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController

/**
 * How the WebView is configured.
 *
 * Several of these assert an *absence*. The page is served by an arbitrary peer,
 * and the settings that would give it a way out of the browser sandbox are the
 * ones most likely to be turned on by accident later.
 */
@RunWith(RobolectricTestRunner::class)
class WebViewConfigTest {

    private lateinit var harness: TestHarness
    private var controller: ActivityController<MainActivity>? = null

    @Before
    fun setUp() {
        harness = TestHarness()
        harness.install()
        harness.seed("ticket-alpha")
    }

    @After
    fun tearDown() {
        controller?.close()
        AppContainer.install(null)
    }

    private fun settings(): WebSettings {
        val created = Robolectric.buildActivity(MainActivity::class.java).setup()
        controller = created
        return created.get().findViewById<WebView>(R.id.web_view).settings
    }

    @Test
    fun `javascript is enabled`() {
        // The dashboard is a web app; without this it is a blank page.
        assertTrue(settings().javaScriptEnabled)
    }

    @Test
    fun `dom storage is enabled`() {
        assertTrue(settings().domStorageEnabled)
    }

    @Test
    fun `file access is off`() {
        val settings = settings()
        assertFalse(settings.allowFileAccess)
        assertFalse(settings.allowContentAccess)
    }

    @Test
    fun `file urls cannot reach other origins`() {
        val settings = settings()
        @Suppress("DEPRECATION")
        assertFalse(settings.allowFileAccessFromFileURLs)
        @Suppress("DEPRECATION")
        assertFalse(settings.allowUniversalAccessFromFileURLs)
    }

    @Test
    fun `mixed content is never allowed`() {
        assertEquals(WebSettings.MIXED_CONTENT_NEVER_ALLOW, settings().mixedContentMode)
    }

    @Test
    fun `pinch to zoom is enabled`() {
        // `builtInZoomControls` is the one that actually enables the gesture,
        // and it defaults to false -- which is why zoom did nothing at first.
        val settings = settings()
        assertTrue(settings.supportZoom())
        assertTrue(settings.builtInZoomControls)
    }

    @Test
    fun `the on-screen zoom buttons are hidden`() {
        // They come with builtInZoomControls and overlay the page.
        assertFalse(settings().displayZoomControls)
    }

    @Test
    fun `pages are laid out the way a browser lays them out`() {
        val settings = settings()
        assertTrue(settings.useWideViewPort)
        assertTrue(settings.loadWithOverviewMode)
    }

    @Test
    fun `the passkey script looks for the bridge the app installs`() {
        // Two halves in two languages, joined by a name. If they drift apart,
        // pages silently get no passkeys.
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val script = context.assets.open(PasskeyBridge.SCRIPT_ASSET).bufferedReader().use { it.readText() }
        assertTrue(script.contains("globalThis.${PasskeyBridge.NAME}"))
    }

    @Test
    fun `no javascript interface is installed`() {
        // The one bridge into the app is the passkey one: a WebMessageListener
        // restricted to the endpoint's origin (see PasskeyWiringTest), whose
        // messages are data. `addJavascriptInterface` would instead hand page
        // JavaScript callable methods in the app process, in every frame of
        // every origin.
        //
        // A call that never happens leaves nothing for reflection to find, so
        // this asserts on the compiled classes instead: the constant pool has
        // no reference to the method at all.
        listOf(MainActivity::class.java, WebViewPasskeyInstaller::class.java).forEach { type ->
            val bytecode = type.classLoader!!
                .getResourceAsStream(type.name.replace('.', '/') + ".class")!!
                .use { it.readBytes() }

            assertFalse(
                "${type.simpleName} references addJavascriptInterface",
                String(bytecode, Charsets.ISO_8859_1).contains("addJavascriptInterface"),
            )
        }
    }
}
