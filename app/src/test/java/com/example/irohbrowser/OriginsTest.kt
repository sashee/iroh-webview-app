package com.example.irohbrowser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Which URLs belong inside the WebView.
 *
 * This is the app's outermost security boundary in the browsing direction: the
 * WebView is privileged relative to the page, and the page comes from an
 * arbitrary peer. Everything that is not unmistakably ours goes to the real
 * browser or nowhere.
 */
@RunWith(RobolectricTestRunner::class)
class OriginsTest {

    private val label = "a1b2c3d4e5f60718"
    private val port = 41234

    private fun destination(url: String?) = Origins.externalDestination(url, label, port)

    @Test
    fun `the url is a loopback origin naming the endpoint`() {
        assertEquals("http://$label.localhost:$port/", Origins.url(label, port))
        assertEquals("http://$label.localhost:$port", Origins.origin(label, port))
    }

    @Test
    fun `our own pages are ours`() {
        assertTrue(Origins.isOwnOrigin("http://$label.localhost:$port/", label, port))
        assertTrue(Origins.isOwnOrigin("http://$label.localhost:$port/deep/path?q=1", label, port))
    }

    @Test
    fun `a different label on the same port is not ours`() {
        // The port is shared by whatever endpoint is running; the label is what
        // identifies it. Treating the port as identity would let one endpoint's
        // page navigate into another's origin.
        assertFalse(Origins.isOwnOrigin("http://other.localhost:$port/", label, port))
    }

    @Test
    fun `our label on a different port is not ours`() {
        assertFalse(Origins.isOwnOrigin("http://$label.localhost:1234/", label, port))
    }

    @Test
    fun `https on our own host is not ours`() {
        assertFalse(Origins.isOwnOrigin("https://$label.localhost:$port/", label, port))
    }

    @Test
    fun `a host that merely ends with ours is not ours`() {
        assertFalse(Origins.isOwnOrigin("http://evil-$label.localhost:$port/", label, port))
        assertFalse(Origins.isOwnOrigin("http://$label.localhost.evil.com:$port/", label, port))
    }

    @Test
    fun `bare loopback is not ours`() {
        // Without the label there is no cookie isolation, so a page loaded this
        // way would share a jar with every endpoint.
        assertFalse(Origins.isOwnOrigin("http://127.0.0.1:$port/", label, port))
        assertFalse(Origins.isOwnOrigin("http://localhost:$port/", label, port))
    }

    @Test
    fun `null and nonsense are not ours`() {
        assertFalse(Origins.isOwnOrigin(null, label, port))
        assertFalse(Origins.isOwnOrigin("", label, port))
        assertFalse(Origins.isOwnOrigin("not a url at all", label, port))
    }

    @Test
    fun `our own pages stay in the webview`() {
        assertEquals(Origins.Destination.Keep, destination("http://$label.localhost:$port/x"))
    }

    @Test
    fun `external web pages go to the real browser`() {
        assertEquals(Origins.Destination.OpenExternally, destination("https://example.com/"))
        assertEquals(Origins.Destination.OpenExternally, destination("http://example.com/"))
    }

    @Test
    fun `intent urls are refused outright`() {
        // An intent: URL can start arbitrary components of other apps.
        assertEquals(
            Origins.Destination.Refuse,
            destination("intent://evil/#Intent;scheme=http;package=com.example;end"),
        )
    }

    @Test
    fun `file and content urls are refused`() {
        assertEquals(Origins.Destination.Refuse, destination("file:///etc/hosts"))
        assertEquals(Origins.Destination.Refuse, destination("content://com.example/secret"))
    }

    @Test
    fun `javascript urls are refused`() {
        assertEquals(Origins.Destination.Refuse, destination("javascript:alert(1)"))
    }

    @Test
    fun `data urls are refused`() {
        assertEquals(Origins.Destination.Refuse, destination("data:text/html,<h1>hi</h1>"))
    }

    @Test
    fun `other app schemes are refused`() {
        assertEquals(Origins.Destination.Refuse, destination("tel:+15550100"))
        assertEquals(Origins.Destination.Refuse, destination("market://details?id=com.example"))
    }

    @Test
    fun `empty and null navigations are refused`() {
        assertEquals(Origins.Destination.Refuse, destination(null))
        assertEquals(Origins.Destination.Refuse, destination(""))
    }
}
