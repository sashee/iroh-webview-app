//! The JNI surface. Four methods, plus one initialiser.
//!
//! Deliberately tiny: everything with any judgement in it lives in the modules
//! this calls, which are testable on the host. What is here is only the
//! translation between JVM types and Rust ones, so there is little to get wrong
//! and nothing to test that a host test could reach.
//!
//! The mutable static is the one piece of shared state in the crate. The JNI
//! boundary has nowhere else for it to live: Kotlin holds no Rust value, so the
//! running proxies have to be findable from a bare function call.

use std::collections::HashMap;
use std::sync::{Mutex, OnceLock};

use jni::{
    objects::{JClass, JObject, JString},
    sys::{jboolean, jint, jstring},
    JNIEnv,
};

use crate::{
    runtime::{self, Running},
    ticket,
};

/// The proxies running, by the label of their endpoint: one per endpoint the
/// app has a card open for.
fn running() -> &'static Mutex<HashMap<String, Running>> {
    static RUNNING: OnceLock<Mutex<HashMap<String, Running>>> = OnceLock::new();
    RUNNING.get_or_init(|| Mutex::new(HashMap::new()))
}

/// Error codes returned in place of a port. Mirrored by `NativeProxy.kt`.
const ERROR_BAD_TICKET: jint = -1;
const ERROR_START_FAILED: jint = -2;
const ERROR_BAD_ARGUMENT: jint = -3;

/// Publish the JVM and application context to the Rust dependencies that call
/// into the platform.
///
/// DNS no longer needs it: the endpoint resolves over DoH (`resolver.rs`) and
/// never reads the network's nameservers. netdev, under netwatch, still reads
/// interface details through JNI, and probes for the context by catching a
/// panic -- which is why the release profile must leave panics unwinding.
///
/// Called once from `Application.onCreate`, with the application context so the
/// global reference we leak is one that lives as long as the process anyway.
#[no_mangle]
pub extern "system" fn Java_com_example_irohbrowser_NativeProxy_nativeInstallContext(
    env: JNIEnv,
    _class: JClass,
    context: JObject,
) {
    // Before anything else, so the checks below and every later refusal are
    // visible in logcat. Without this the Rust half is entirely silent and a
    // refused connection is indistinguishable from a broken proxy.
    android_logger::init_once(
        android_logger::Config::default()
            .with_max_level(log::LevelFilter::Info)
            // `android_logger` routes the whole `log` facade, so the
            // dependencies log under our tag too. At Info, iroh reports every
            // path event and every send -- on a Pixel 6a that buried our own
            // lines completely -- so they get Warn and only this crate gets
            // Info. The longest matching prefix wins, which is what keeps
            // `iroh_webview_proxy` out of `iroh`'s directive.
            //
            // `tracing` is there because a span without fields reaches `log`
            // under the target "tracing::span", not its own module: the
            // `iroh::...` that logcat shows in front of those lines is the
            // module path android_logger prints, which the filter never sees.
            // Raise these when chasing a transport problem.
            .with_filter(
                android_logger::FilterBuilder::new()
                    .parse(
                        "info,iroh=warn,noq=warn,n0_dns_resolver=warn,rustls=warn,\
                         netwatch=warn,portmapper=warn,tracing=warn,\
                         iroh_webview_proxy=info",
                    )
                    .build(),
            )
            .with_tag("irohbrowser"),
    );
    log::info!("native library initialising");

    let Ok(vm) = env.get_java_vm() else { return };
    let Ok(context) = env.new_global_ref(context) else {
        return;
    };
    // Both pointers must stay valid until the process exits, so the global ref
    // is deliberately never dropped.
    let context = std::mem::ManuallyDrop::new(context);
    unsafe {
        iroh::dns::install_android_jni_context(
            vm.get_java_vm_pointer() as *mut std::ffi::c_void,
            context.as_raw() as *mut std::ffi::c_void,
        );
    }
}

/// Start the proxy for `ticket`, returning the bound loopback port, or a
/// negative error code. One already running for the same endpoint is replaced,
/// and stopped; those of other endpoints go on.
#[no_mangle]
pub extern "system" fn Java_com_example_irohbrowser_NativeProxy_nativeStart(
    mut env: JNIEnv,
    _class: JClass,
    ticket: JString,
) -> jint {
    let Ok(ticket) = env.get_string(&ticket) else {
        return ERROR_BAD_ARGUMENT;
    };
    let ticket: String = ticket.into();

    match runtime::start(&ticket) {
        Ok(running) => {
            let port = running.port() as jint;
            log::info!("proxy listening on 127.0.0.1:{port} as {}", running.label());
            // Replaces this endpoint's, if one was running: its Drop stops it,
            // once the lock is let go, as in nativeStop.
            let replaced = lock().insert(running.label().to_string(), running);
            drop(replaced);
            port
        }
        Err(runtime::StartError::Ticket(cause)) => {
            log::warn!("rejected ticket: {cause}");
            ERROR_BAD_TICKET
        }
        Err(runtime::StartError::Io(cause)) => {
            log::error!("could not start: {cause}");
            ERROR_START_FAILED
        }
    }
}

/// The label and preferred port of the endpoint `ticket` names, as
/// `"<label>:<port>"`, or null when it is not a ticket. Starts nothing.
#[no_mangle]
pub extern "system" fn Java_com_example_irohbrowser_NativeProxy_nativeIdentity(
    mut env: JNIEnv,
    _class: JClass,
    ticket: JString,
) -> jstring {
    let identity = env
        .get_string(&ticket)
        .ok()
        .and_then(|ticket| ticket::identity(&String::from(ticket)).ok())
        .map(|(label, port)| format!("{label}:{port}"));
    match identity.and_then(|identity| env.new_string(identity).ok()) {
        Some(identity) => identity.into_raw(),
        None => JObject::null().into_raw() as jstring,
    }
}

/// Android blocked this app's network (`blocked`), or gave it back: every
/// running proxy closes the connections it has open ([`Running::network_blocked`]).
#[no_mangle]
pub extern "system" fn Java_com_example_irohbrowser_NativeProxy_nativeNetworkBlocked(
    _env: JNIEnv,
    _class: JClass,
    blocked: jboolean,
) {
    let running = lock();
    if running.is_empty() {
        return;
    }
    let blocked = blocked != 0;
    log::info!(
        "network {}: closing the proxies' connections",
        if blocked { "blocked by Android" } else { "back" }
    );
    running.values().for_each(|proxy| proxy.network_blocked(blocked));
}

/// Stop the proxy of the endpoint labelled `label`, if one is running.
/// Idempotent; the others go on.
#[no_mangle]
pub extern "system" fn Java_com_example_irohbrowser_NativeProxy_nativeStop(
    mut env: JNIEnv,
    _class: JClass,
    label: JString,
) {
    let Ok(label) = env.get_string(&label) else { return };
    // Taken out first and dropped once the lock is let go: stopping waits for the
    // proxy's runtime, and the others shouldn't wait on that.
    let stopped = lock().remove(&String::from(label));
    drop(stopped);
}

/// The lock, recovered from poisoning.
///
/// A panic while holding it would otherwise make every proxy unstartable for
/// the rest of the process, which is a worse outcome than proceeding with
/// whatever the panicking call left behind -- a map of running proxies has no
/// invariant a panic could break.
fn lock() -> std::sync::MutexGuard<'static, HashMap<String, Running>> {
    running().lock().unwrap_or_else(|poisoned| poisoned.into_inner())
}
