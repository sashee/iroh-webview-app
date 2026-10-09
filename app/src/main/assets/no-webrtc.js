// Takes WebRTC away from pages.
//
// The app installs this at document start, for every origin
// (WebViewCompat.addDocumentStartJavaScript). The WebView is kept off the
// network except through the tunnel (Confinement.kt), but WebRTC's UDP passes
// beneath both of the checks that do that: a page could ask a STUN server
// anywhere for the phone's public address. Without a peer connection there is
// no ICE, so removing the constructors is enough, and a page that tests for
// them carries on as it would in a browser without WebRTC.
//
// Best-effort: a page set on having it can still find a fresh copy in an
// iframe this script never ran in. It stops scripts that use WebRTC in passing,
// not one written against this app.
for (const name of ["RTCPeerConnection", "webkitRTCPeerConnection"]) delete globalThis[name];
