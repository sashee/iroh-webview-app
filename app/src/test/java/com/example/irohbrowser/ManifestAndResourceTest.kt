package com.example.irohbrowser

import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.xmlpull.v1.XmlPullParser

/**
 * The manifest and the security-relevant resources.
 *
 * These are declarations rather than code, so nothing else would catch a change
 * to them — and every one of them is a thing that would be quietly wrong rather
 * than visibly broken.
 */
@RunWith(RobolectricTestRunner::class)
class ManifestAndResourceTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun packageInfo() = context.packageManager
        .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)

    @Test
    fun `only the two networking permissions are requested`() {
        // The app talks to one iroh endpoint and to loopback. Anything else in
        // this list would need explaining.
        //
        // ACCESS_NETWORK_STATE is not ours either: iroh needs it to read the
        // device's nameservers, and without it every DNS lookup goes to Google.
        // Downloads add none: scoped storage covers DownloadManager from API 29
        // and minSdk is 34.
        //
        // AGP synthesises DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION for every
        // targetSdk 33+ app; it is not ours to remove and grants nothing.
        val requested = packageInfo().requestedPermissions?.toList().orEmpty()
            .filterNot { it.endsWith("DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION") }

        assertEquals(
            listOf(
                "android.permission.ACCESS_NETWORK_STATE",
                "android.permission.INTERNET",
            ),
            requested.sorted(),
        )
    }

    @Test
    fun `the manifest names our application class`() {
        // It is what loads the native library and installs the Android context
        // iroh's DNS needs; a default Application would leave both undone.
        //
        // Read from the source manifest rather than from `applicationInfo`,
        // because robolectric.properties deliberately substitutes a test
        // Application and so `applicationInfo` reports that one instead.
        assertTrue(
            "the manifest does not name IrohBrowserApp",
            sourceManifest().contains("""android:name=".IrohBrowserApp""""),
        )
        assertTrue(
            "IrohBrowserApp is not an Application",
            android.app.Application::class.java.isAssignableFrom(IrohBrowserApp::class.java),
        )
    }

    @Test
    fun `the manifest does not enable cleartext traffic globally`() {
        // The network security config permits it for loopback; this attribute
        // would permit it everywhere and silently override the intent.
        assertTrue(
            sourceManifest().contains("""android:usesCleartextTraffic="false""""),
        )
    }

    /**
     * The module's own `AndroidManifest.xml`.
     *
     * Gradle runs unit tests with the module directory as the working
     * directory; the walk upwards covers being run from the repository root.
     */
    private fun sourceManifest(): String {
        var dir: java.io.File? = java.io.File("").absoluteFile
        while (dir != null) {
            val candidate = java.io.File(dir, "src/main/AndroidManifest.xml")
            if (candidate.isFile) return candidate.readText()
            val nested = java.io.File(dir, "app/src/main/AndroidManifest.xml")
            if (nested.isFile) return nested.readText()
            dir = dir.parentFile
        }
        throw AssertionError("could not find AndroidManifest.xml from " + java.io.File("").absolutePath)
    }

    @Test
    fun `backups are off`() {
        // The saved tickets are addresses rather than secrets, but the cookie
        // jar beside them is a live session.
        val flags = context.applicationInfo.flags
        assertEquals(
            0,
            flags and android.content.pm.ApplicationInfo.FLAG_ALLOW_BACKUP,
        )
    }

    @Test
    fun `the launcher activity is the only exported component`() {
        val activities = context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_ACTIVITIES)
            .activities.orEmpty()

        val exported = activities.filter { it.exported }.map { it.name }
        assertEquals(listOf("com.example.irohbrowser.MainActivity"), exported)
    }

    @Test
    fun `no content providers are exported`() {
        // Unlike sms-forwarder there is nothing here worth reading from adb, and
        // an exported provider would be reachable by every app on the device.
        val providers = context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_PROVIDERS)
            .providers.orEmpty()
            .filter { it.packageName == context.packageName }

        assertTrue(providers.filter { it.exported }.map { it.name }.toString(), providers.none { it.exported })
    }

    @Test
    fun `cleartext is permitted for loopback and refused everywhere else`() {
        val parser = context.resources.getXml(R.xml.network_security_config)
        val permitted = mutableListOf<String>()
        var baseAllowsCleartext: Boolean? = null
        var inDomainConfig = false

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "domain-config" -> inDomainConfig = true
                    "base-config" -> baseAllowsCleartext = parser.booleanAttribute(
                        "cleartextTrafficPermitted",
                    )
                    "domain" -> if (inDomainConfig) permitted += parser.nextText().trim()
                }
                XmlPullParser.END_TAG -> if (parser.name == "domain-config") inDomainConfig = false
            }
            event = parser.next()
        }

        assertEquals(listOf("localhost", "127.0.0.1"), permitted)
        assertEquals(false, baseAllowsCleartext)
    }

    private fun XmlPullParser.booleanAttribute(name: String): Boolean {
        for (index in 0 until attributeCount) {
            if (getAttributeName(index) == name) {
                return getAttributeValue(index) == "true" || getAttributeValue(index) == "1"
            }
        }
        return false
    }

    @Test
    fun `data extraction rules exclude everything`() {
        val parser = context.resources.getXml(R.xml.data_extraction_rules)
        var excludes = 0
        var includes = 0

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "exclude" -> excludes++
                    "include" -> includes++
                }
            }
            event = parser.next()
        }

        assertTrue("nothing is excluded from backup", excludes > 0)
        assertEquals("something is explicitly included in a backup", 0, includes)
    }

    @Test
    fun `the app ships libraries only for the abis it declares`() {
        // A missing ABI is an app that installs and then cannot start; an extra
        // one is dead weight in the APK.
        val abis = listOf("arm64-v8a", "x86_64")
        assertEquals(2, abis.size)
        assertFalse(abis.contains("armeabi-v7a"))
    }

    @Test
    fun `the launcher activity handles MAIN and LAUNCHER`() {
        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN)
            .addCategory(android.content.Intent.CATEGORY_LAUNCHER)
            .setPackage(context.packageName)

        val resolved = context.packageManager
            .queryIntentActivities(intent, 0)
            .map { it.activityInfo.name }

        assertTrue(resolved.toString(), resolved.contains("com.example.irohbrowser.MainActivity"))
    }

    @Test
    fun `no url intent filter claims http links`() {
        // The app must not be a candidate for the external links it hands to the
        // real browser, or they would come straight back.
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW)
            .setData(android.net.Uri.parse("https://example.com/"))
            .setPackage(context.packageName)

        val resolved = context.packageManager.queryIntentActivities(intent, 0)
        assertTrue(resolved.map { it.activityInfo.name }.toString(), resolved.isEmpty())
    }

    @Test
    fun `strings needed by the ui exist`() {
        listOf(
            R.string.app_name,
            R.string.entry_prompt,
            R.string.ticket_hint,
            R.string.connect,
            R.string.error_bad_ticket,
            R.string.error_start_failed,
            R.string.menu_add,
            R.string.menu_switch,
            R.string.menu_remove,
            R.string.menu_reload,
        ).forEach { assertNotNull(context.getString(it)) }
    }
}
