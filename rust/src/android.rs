//! The JNI surface. Two methods, plus one initialiser.
//!
//! Deliberately tiny: everything with any judgement in it lives in the modules
//! this calls, which are testable on the host. What is here is only the
//! translation between JVM types and Rust ones, so there is little to get wrong
//! and nothing to test that a host test could reach.
//!
//! The mutable static is the one piece of shared state in the crate. The JNI
//! boundary has nowhere else for it to live: Kotlin holds no Rust value, so the
//! running proxy has to be findable from a bare function call.

use std::sync::{Mutex, OnceLock};

use jni::{
    objects::{JClass, JObject, JString},
    sys::{jint, jstring},
    JNIEnv,
};

use crate::runtime::{self, Running};

/// The proxy, if one is running. At most one: the app switches endpoints rather
/// than running several.
fn current() -> &'static Mutex<Option<Running>> {
    static CURRENT: OnceLock<Mutex<Option<Running>>> = OnceLock::new();
    CURRENT.get_or_init(|| Mutex::new(None))
}

/// Error codes returned in place of a port. Mirrored by `NativeProxy.kt`.
const ERROR_BAD_TICKET: jint = -1;
const ERROR_START_FAILED: jint = -2;
const ERROR_BAD_ARGUMENT: jint = -3;

/// Give iroh a way to read the active network's nameservers.
///
/// Android has no `/etc/resolv.conf`; `iroh_dns` goes through
/// `LinkProperties.getDnsServers()` over JNI instead, and needs an application
/// context installed before the first resolver is built. Without this, iroh
/// catches the resulting panic and falls back to public nameservers — which is
/// why the release profile must leave panics unwinding.
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
            // Info, not Debug: `android_logger` routes the whole `log` facade,
            // so Debug pulls in iroh, rustls and hickory and buries our own
            // lines. Raise it when chasing a transport problem.
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
/// negative error code.
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
            // Replaces whatever was running, whose Drop stops it.
            *lock() = Some(running);
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

/// The DNS label for the running proxy's origin, or null if none is running.
#[no_mangle]
pub extern "system" fn Java_com_example_irohbrowser_NativeProxy_nativeLabel(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    let label = lock().as_ref().map(|running| running.label().to_string());
    match label.and_then(|label| env.new_string(label).ok()) {
        Some(label) => label.into_raw(),
        None => JObject::null().into_raw() as jstring,
    }
}

/// Stop the proxy, if one is running. Idempotent.
#[no_mangle]
pub extern "system" fn Java_com_example_irohbrowser_NativeProxy_nativeStop(
    _env: JNIEnv,
    _class: JClass,
) {
    *lock() = None;
}

/// The lock, recovered from poisoning.
///
/// A panic while holding it would otherwise make the proxy unstartable for the
/// rest of the process, which is a worse outcome than proceeding with whatever
/// the panicking call left behind — an `Option<Running>` has no invariant a
/// panic could break.
fn lock() -> std::sync::MutexGuard<'static, Option<Running>> {
    current().lock().unwrap_or_else(|poisoned| poisoned.into_inner())
}
