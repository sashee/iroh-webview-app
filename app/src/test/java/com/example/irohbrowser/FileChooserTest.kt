package com.example.irohbrowser

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.webkit.WebChromeClient.FileChooserParams
import android.webkit.WebView
import com.example.irohbrowser.testing.TestHarness
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController

/**
 * A page's `<input type="file">`: which picker opens, and what the page gets
 * back. A file input whose callback is never answered stays disabled for good.
 */
@RunWith(RobolectricTestRunner::class)
class FileChooserTest {

    private lateinit var harness: TestHarness
    private var controller: ActivityController<MainActivity>? = null

    /** What the page was answered with, each time. */
    private val answers = mutableListOf<Array<Uri>?>()

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

    private fun launch(): MainActivity {
        val created = Robolectric.buildActivity(MainActivity::class.java).setup()
        controller = created
        return created.get()
    }

    private fun params(mode: Int, accept: Array<String> = emptyArray()) = object : FileChooserParams() {
        override fun getMode() = mode
        override fun getAcceptTypes() = accept
        override fun isCaptureEnabled() = false
        override fun getTitle(): CharSequence? = null
        override fun getFilenameHint(): String? = null
        override fun createIntent() = Intent()
    }

    /** The page asks for files or a folder; returns the picker that opened. */
    private fun MainActivity.choose(mode: Int, accept: Array<String> = emptyArray()): Intent {
        val webView = findViewById<WebView>(R.id.web_view)
        assertTrue(shadowOf(webView).webChromeClient.onShowFileChooser(webView, { answers += it }, params(mode, accept)))
        return shadowOf(this).nextStartedActivityForResult.intent
    }

    private fun MainActivity.pick(picker: Intent, result: Intent?) =
        shadowOf(this).receiveResult(picker, if (result == null) Activity.RESULT_CANCELED else Activity.RESULT_OK, result)

    @Test
    fun `a file input opens the document picker, for several files of the types it accepts`() {
        val activity = launch()
        val picker = activity.choose(FileChooserParams.MODE_OPEN_MULTIPLE, arrayOf("audio/*", ""))
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, picker.action)
        assertTrue(picker.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false))
        assertArrayEquals(arrayOf("audio/*"), picker.getStringArrayExtra(Intent.EXTRA_MIME_TYPES))

        val picked = listOf(Uri.parse("content://docs/a.mp3"), Uri.parse("content://docs/b.mp3"))
        activity.pick(picker, Intent().apply {
            clipData = android.content.ClipData.newRawUri(null, picked[0]).apply { addItem(android.content.ClipData.Item(picked[1])) }
        })
        assertEquals(picked, answers.single()!!.toList())
    }

    @Test
    fun `a folder input opens the folder picker, and the page gets the folder`() {
        val activity = launch()
        val picker = activity.choose(FileChooserParams.MODE_OPEN_FOLDER)
        assertEquals(Intent.ACTION_OPEN_DOCUMENT_TREE, picker.action)

        val folder = Uri.parse("content://docs/tree/Music")
        activity.pick(picker, Intent().setData(folder))
        assertEquals(listOf(folder), answers.single()!!.toList())
    }

    @Test
    fun `a pick cancelled, or superseded by another, answers the page with nothing`() {
        val activity = launch()
        activity.choose(FileChooserParams.MODE_OPEN_FOLDER)
        val second = activity.choose(FileChooserParams.MODE_OPEN_MULTIPLE)
        activity.pick(second, null)

        assertEquals(2, answers.size)
        answers.forEach { assertTrue(it == null || it.isEmpty()) }
    }

    @Test
    fun `closing the app answers a page still waiting`() {
        val activity = launch()
        activity.choose(FileChooserParams.MODE_OPEN_FOLDER)
        controller!!.pause().stop().destroy()
        controller = null
        assertNull(answers.single())
    }
}
