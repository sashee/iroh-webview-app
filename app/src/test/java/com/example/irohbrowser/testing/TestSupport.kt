package com.example.irohbrowser.testing

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import com.example.irohbrowser.AppContainer
import com.example.irohbrowser.EndpointStore
import com.example.irohbrowser.EndpointIdentity
import com.example.irohbrowser.Endpoints
import com.example.irohbrowser.KeyOperation
import com.example.irohbrowser.KeyStorage
import com.example.irohbrowser.KeyVault
import com.example.irohbrowser.Outcome
import com.example.irohbrowser.PageScripts
import com.example.irohbrowser.PasskeyError
import com.example.irohbrowser.PasskeyInstaller
import com.example.irohbrowser.PasskeyPlatform
import com.example.irohbrowser.PasskeyPurpose
import com.example.irohbrowser.PasskeyReceiver
import com.example.irohbrowser.PasskeyStore
import com.example.irohbrowser.PasskeyUi
import com.example.irohbrowser.ProxyBinding
import com.example.irohbrowser.ProxyController
import com.example.irohbrowser.ProxyError
import com.example.irohbrowser.ProxyResult
import com.example.irohbrowser.RequestCheck
import com.example.irohbrowser.ServiceWorkerRequests
import com.example.irohbrowser.SiteData
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

/**
 * A [ProxyController] that records what it was asked to do and hands back a
 * port without binding anything.
 *
 * The real one loads a native library cross-compiled for a phone, which a
 * Robolectric test on the host cannot open. What the activity has to get right
 * is the *order* — stop, clear, start, load — and that is visible here.
 */
class FakeProxy : ProxyController {

    /** Every call, in order, as `"start:<ticket>"` or `"stop"`. */
    val calls = mutableListOf<String>()

    /** Tickets that should fail, and how. */
    val failures = mutableMapOf<String, ProxyError>()

    /** Ports handed out, so each start is distinguishable from the last. */
    private var nextPort = 40000

    /**
     * Bind each endpoint's preferred port, as the real proxy does when nothing
     * else holds it. Off by default, which models the fallback: every start
     * gets a port no page was saved on.
     */
    var bindPreferredPorts = false

    /** Runs as each start begins, so a test can see what had happened by then. */
    var onStart: () -> Unit = {}

    var running: ProxyBinding? = null
        private set

    override fun start(ticket: String): ProxyResult {
        onStart()
        calls += "start:$ticket"
        failures[ticket]?.let {
            running = null
            return ProxyResult.Failed(it)
        }
        val port = if (bindPreferredPorts) preferredPortFor(ticket) else nextPort++
        val binding = ProxyBinding(label = labelFor(ticket), port = port)
        running = binding
        return ProxyResult.Started(binding)
    }

    override fun stop() {
        calls += "stop"
        running = null
    }

    /**
     * A preferred port that `start` never hands out, so a test can tell which
     * of the two an origin was built from.
     */
    override fun identify(ticket: String): EndpointIdentity? =
        if (failures[ticket] == ProxyError.BadTicket) {
            null
        } else {
            EndpointIdentity(labelFor(ticket), preferredPortFor(ticket))
        }

    fun preferredPortFor(ticket: String): Int = 20000 + ticket.hashCode().mod(10000)

    /**
     * Stands in for the Rust side's endpoint-id derivation: stable per ticket,
     * distinct between tickets, and a valid DNS label.
     */
    fun labelFor(ticket: String): String =
        ticket.filter { it.isLetterOrDigit() }.lowercase().takeLast(16).ifEmpty { "endpoint" }

    val startedTickets: List<String>
        get() = calls.filter { it.startsWith("start:") }.map { it.removePrefix("start:") }
}

/** A [SiteData] that records rather than touching the framework's stores. */
class FakeSiteData : SiteData {
    var flushes = 0
        private set

    /** Origins cleared, in order. */
    val cleared = mutableListOf<String>()

    val clears: Int
        get() = cleared.size

    override fun flush() {
        flushes++
    }

    override fun clear(origin: String) {
        cleared += origin
    }
}

/**
 * A [KeyVault] with software keys.
 *
 * Signs without anyone's finger -- the opposite of the real one, which is the
 * point: what the tests check is what is signed and when, and that the result
 * verifies, not the Keystore's own enforcement. The one piece of enforcement
 * kept is the window key's: it will not prime a signature unless a touch has
 * just happened, because getting that order wrong would work in a test and
 * fail on every phone.
 */
class FakeKeyVault : KeyVault {
    val keys = mutableMapOf<String, KeyPair>()

    /** PRF keys, as their raw HMAC secrets so a test can compute what the results should be. */
    val prfKeys = mutableMapOf<String, ByteArray>()

    /** Signing keys created to accept a recent touch rather than one per signature. */
    val windowKeys = mutableSetOf<String>()

    /** Aliases whose keys behave as if a new fingerprint had invalidated them. */
    val invalidated = mutableSetOf<String>()

    /** Make [create] fail, as it does on a phone with no fingerprint or screen lock. */
    var refuseToCreate = false

    /** Make [createPrf] fail, as on a phone whose Keystore has no HMAC keys. */
    var refuseToCreatePrf = false

    /** Whether a touch happened recently enough for window keys. Set when the fake prompt approves. */
    var touchedRecently = false

    override fun create(alias: String, touchWindow: Boolean): ECPublicKey {
        if (refuseToCreate) throw IllegalStateException("no screen lock")
        val pair = KeyPairGenerator.getInstance("EC")
            .apply { initialize(ECGenParameterSpec("secp256r1")) }
            .generateKeyPair()
        keys[alias] = pair
        if (touchWindow) windowKeys += alias
        return pair.public as ECPublicKey
    }

    override fun signer(alias: String): Signature? {
        if (alias in invalidated) return null
        val pair = keys[alias] ?: return null
        if (alias in windowKeys && !touchedRecently) throw IllegalStateException("User not authenticated")
        return Signature.getInstance("SHA256withECDSA").apply { initSign(pair.private) }
    }

    override fun createPrf(alias: String) {
        if (refuseToCreatePrf) throw IllegalStateException("no HMAC keys here")
        prfKeys[alias] = Random.nextBytes(32)
    }

    override fun prf(alias: String): Mac? {
        if (alias in invalidated) return null
        val secret = prfKeys[alias] ?: return null
        return Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret, "HmacSHA256")) }
    }

    override fun delete(alias: String) {
        keys.remove(alias)
        prfKeys.remove(alias)
        windowKeys.remove(alias)
    }

    override fun storage(alias: String): KeyStorage? =
        if (alias in keys || alias in prfKeys) KeyStorage.Software else null
}

/** A [PasskeyUi] whose user does what the test says. */
class FakePasskeyUi : PasskeyUi {
    data class Prompt(val purpose: PasskeyPurpose, val account: String, val endpointName: String)

    enum class Verdict { Approve, Refuse, Hold }

    val prompts = mutableListOf<Prompt>()

    /** What each prompt was asked to unlock. */
    val operations = mutableListOf<KeyOperation>()

    /** Runs as a touch is given -- where the real Keystore starts honouring window keys. */
    var onApprove: () -> Unit = {}

    /** Every account list the chooser was shown. */
    val chooserShown = mutableListOf<List<String>>()

    var verdict = Verdict.Approve

    /** The chooser's answer: an index, or null for "declined". */
    var choice: Int? = 0

    /** Prompts withdrawn by a cancel. */
    var withdrawn = 0
        private set

    private var held: (() -> Unit)? = null

    override fun choose(endpointName: String, accounts: List<String>, chosen: (Int?) -> Unit): () -> Unit {
        chooserShown += accounts
        chosen(choice)
        return {}
    }

    override fun verify(
        purpose: PasskeyPurpose,
        account: String,
        endpointName: String,
        operation: KeyOperation,
        done: (Outcome<KeyOperation>) -> Unit,
    ): () -> Unit {
        prompts += Prompt(purpose, account, endpointName)
        operations += operation
        val refusal = Outcome.Failed(PasskeyError.notAllowed("cancelled"))
        val approve = {
            onApprove()
            done(Outcome.Ok(operation))
        }
        when (verdict) {
            Verdict.Approve -> approve()
            Verdict.Refuse -> done(refusal)
            Verdict.Hold -> held = approve
        }
        // Like BiometricPrompt: withdrawing a prompt still answers it, with an error.
        return {
            if (held != null) {
                held = null
                withdrawn++
                done(refusal)
            }
        }
    }

    /** Put a finger on the sensor for a held prompt. */
    fun approveHeld() {
        val answer = held ?: error("no prompt is waiting")
        held = null
        answer()
    }
}

/** A [PasskeyInstaller] that records where the bridge was offered, and lets a test post to it. */
class FakePasskeyInstaller : PasskeyInstaller {
    /** Every install and uninstall, in order, as `"install:<origin>"` and `"uninstall:<origin>"`. */
    val events = mutableListOf<String>()

    /** Act like a WebView without the features the bridge needs. */
    var supported = true

    var installedOrigin: String? = null
        private set

    private var receiver: PasskeyReceiver? = null

    /** Runs as each install begins, so a test can see what had happened by then. */
    var onInstall: () -> Unit = {}

    override fun install(webView: WebView, origin: String, receive: PasskeyReceiver): (() -> Unit)? {
        onInstall()
        if (!supported) return null
        events += "install:$origin"
        installedOrigin = origin
        receiver = receive
        return {
            events += "uninstall:$origin"
            installedOrigin = null
            receiver = null
        }
    }

    /** Post [message] as a page of [from] would, and return what came back. */
    fun post(message: String, from: String = installedOrigin ?: error("nothing installed")): List<String> {
        val replies = mutableListOf<String>()
        (receiver ?: error("nothing installed"))(message, from) { replies += it }
        return replies
    }
}

/** The passkey half of the container, faked. */
class FakePasskeys(context: Context) {
    val installer = FakePasskeyInstaller()
    val vault = FakeKeyVault()
    val ui = FakePasskeyUi()
    val store = PasskeyStore(context.getSharedPreferences("passkeys-test", Context.MODE_PRIVATE))
    private val random = Random(7)

    init {
        ui.onApprove = { vault.touchedRecently = true }
    }

    val platform = PasskeyPlatform(
        installer = installer,
        store = store,
        vault = vault,
        ui = { ui },
        random = random::nextBytes,
        clock = { 1_700_000_000_000 },
    )
}

/** Records the check service worker requests are routed through, so a test can apply it. */
class FakeServiceWorkerRequests : ServiceWorkerRequests {
    var check: RequestCheck? = null
        private set

    override fun route(check: RequestCheck?) {
        this.check = check
    }
}

/** Records the scripts installed into pages, in order. */
class FakePageScripts : PageScripts {
    val installed = mutableListOf<String>()

    override fun install(webView: WebView, script: String) {
        installed += script
    }
}

/** A main-frame GET of [url], as the WebView hands it to its clients. */
fun request(url: String, gesture: Boolean = false): WebResourceRequest = object : WebResourceRequest {
    override fun getUrl(): Uri = Uri.parse(url)
    override fun isForMainFrame() = true
    override fun isRedirect() = false
    override fun hasGesture() = gesture
    override fun getMethod() = "GET"
    override fun getRequestHeaders(): Map<String, String> = emptyMap()
}

/** Everything a test needs to drive the activity. */
class TestHarness(
    val proxy: FakeProxy = FakeProxy(),
    val siteData: FakeSiteData = FakeSiteData(),
) {
    private val context: Context = ApplicationProvider.getApplicationContext()

    val store: EndpointStore =
        EndpointStore(context.getSharedPreferences("endpoints-test", Context.MODE_PRIVATE))

    val passkeys = FakePasskeys(context)

    val serviceWorkers = FakeServiceWorkerRequests()

    val pageScripts = FakePageScripts()

    fun install() {
        store.save(Endpoints())
        AppContainer.install(AppContainer(store, proxy, passkeys.platform, serviceWorkers, pageScripts) { siteData })
    }

    /** Seed the saved endpoints before the activity starts. */
    fun seed(vararg tickets: String, selected: Int = tickets.size - 1, named: Boolean = true) {
        var endpoints = Endpoints()
        tickets.forEach {
            endpoints = endpoints.add(com.example.irohbrowser.Endpoint(it, it.takeIf { named }))
        }
        store.save(endpoints.select(selected))
    }
}
