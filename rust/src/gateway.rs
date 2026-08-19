//! The one place the proxy writes HTTP rather than forwarding it.
//!
//! Everything else here is byte-transparent, which is the whole point of the
//! design — but a failed dial has no bytes to forward, and closing the socket in
//! silence gets the WebView's "webpage not available" screen, which says nothing
//! about *why*. So a dial failure writes one canned response and closes.
//!
//! This does not make the proxy an HTTP parser: nothing is read or interpreted,
//! a fixed byte string is written. It is wrong only if the client was not
//! speaking HTTP, and the only client is the app's own WebView.

/// A `502` naming what went wrong, with `Connection: close` so the WebView does
/// not try to reuse the socket we are about to drop.
pub fn bad_gateway(reason: &str) -> Vec<u8> {
    let body = format!(
        "<!doctype html>\
         <html><head><meta charset=\"utf-8\">\
         <meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">\
         <title>Endpoint unreachable</title></head>\
         <body><h1>Endpoint unreachable</h1><p>{}</p></body></html>",
        escape(reason)
    );
    format!(
        "HTTP/1.1 502 Bad Gateway\r\n\
         Content-Type: text/html; charset=utf-8\r\n\
         Content-Length: {}\r\n\
         Cache-Control: no-store\r\n\
         Connection: close\r\n\
         \r\n\
         {body}",
        body.len()
    )
    .into_bytes()
}

/// Minimal HTML escaping. The reason text comes from iroh's error types rather
/// than from the page, but it ends up inside a document either way.
fn escape(text: &str) -> String {
    text.chars()
        .flat_map(|c| match c {
            '&' => "&amp;".chars().collect::<Vec<_>>(),
            '<' => "&lt;".chars().collect(),
            '>' => "&gt;".chars().collect(),
            '"' => "&quot;".chars().collect(),
            other => vec![other],
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn response(reason: &str) -> String {
        String::from_utf8(bad_gateway(reason)).expect("utf-8")
    }

    #[test]
    fn starts_with_a_502_status_line() {
        assert!(response("nope").starts_with("HTTP/1.1 502 Bad Gateway\r\n"));
    }

    #[test]
    fn closes_the_connection() {
        assert!(response("nope").contains("\r\nConnection: close\r\n"));
    }

    #[test]
    fn is_not_cached() {
        // A cached error page would survive the endpoint coming back.
        assert!(response("nope").contains("\r\nCache-Control: no-store\r\n"));
    }

    #[test]
    fn content_length_matches_the_body() {
        let text = response("nope");
        let (head, body) = text.split_once("\r\n\r\n").expect("header/body split");
        let declared: usize = head
            .lines()
            .find_map(|line| line.strip_prefix("Content-Length: "))
            .expect("content-length")
            .trim()
            .parse()
            .expect("a number");
        assert_eq!(declared, body.len());
    }

    #[test]
    fn the_reason_reaches_the_page() {
        assert!(response("dial timed out").contains("dial timed out"));
    }

    #[test]
    fn markup_in_the_reason_is_escaped() {
        let text = response("<script>alert(1)</script> & \"quoted\"");
        assert!(!text.contains("<script>"));
        assert!(text.contains("&lt;script&gt;"));
        assert!(text.contains("&amp;"));
        assert!(text.contains("&quot;quoted&quot;"));
    }

    #[test]
    fn escaping_is_still_length_consistent() {
        // The Content-Length is computed after escaping, so an escaped reason
        // must not desynchronise it.
        let text = response("<<<&&&>>>");
        let (head, body) = text.split_once("\r\n\r\n").expect("header/body split");
        let declared: usize = head
            .lines()
            .find_map(|line| line.strip_prefix("Content-Length: "))
            .expect("content-length")
            .trim()
            .parse()
            .expect("a number");
        assert_eq!(declared, body.len());
    }

    #[test]
    fn headers_end_with_a_blank_line() {
        assert_eq!(response("nope").matches("\r\n\r\n").count(), 1);
    }
}
