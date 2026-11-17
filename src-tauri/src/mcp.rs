//! Assistant (MCP) endpoint: a Streamable HTTP server on 127.0.0.1 that authenticates requests and
//! forwards each JSON-RPC body to the web app, which implements the protocol and tools
//! (finance.shilling.shared.mcp.McpServer). The web app turns it on and off from Settings.
//!
//! Flow: POST /mcp → check Host/Origin and bearer token → emit `mcp-request {id, body}` →
//! web app calls `mcp_respond {id, body}` → reply 200 with the JSON (or 202 when body is null).

use std::collections::HashMap;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use axum::body::Bytes;
use axum::extract::{DefaultBodyLimit, State};
use axum::http::{header, HeaderMap, StatusCode};
use axum::response::{IntoResponse, Response};
use axum::routing::post;
use axum::Router;
use serde::Serialize;
use subtle::ConstantTimeEq;
use tauri::{AppHandle, Emitter};
use tokio::sync::oneshot;

const PATH: &str = "/mcp";
const REQUEST_TIMEOUT: Duration = Duration::from_secs(60);
const MAX_BODY_BYTES: usize = 4 * 1024 * 1024;

type Pending = Arc<Mutex<HashMap<u64, oneshot::Sender<Option<String>>>>>;

#[derive(Default)]
pub struct McpState {
    running: Mutex<Option<oneshot::Sender<()>>>,
    pending: Pending,
    next_id: Arc<AtomicU64>,
}

struct Ctx {
    app: AppHandle,
    token: String,
    port: u16,
    pending: Pending,
    next_id: Arc<AtomicU64>,
}

#[derive(Clone, Serialize)]
struct McpRequest {
    id: u64,
    body: String,
}

#[tauri::command]
pub async fn mcp_start(
    app: AppHandle,
    state: tauri::State<'_, McpState>,
    port: u16,
    token: String,
) -> Result<(), String> {
    if token.len() < 32 {
        return Err("Token is too short".into());
    }
    stop(&state);
    let listener = bind(port).await?;
    let ctx = Arc::new(Ctx {
        app,
        token,
        port,
        pending: state.pending.clone(),
        next_id: state.next_id.clone(),
    });
    let router = Router::new()
        .route(PATH, post(handle))
        .layer(DefaultBodyLimit::max(MAX_BODY_BYTES))
        .with_state(ctx);
    let (shutdown_tx, shutdown_rx) = oneshot::channel::<()>();
    *state.running.lock().unwrap() = Some(shutdown_tx);
    tauri::async_runtime::spawn(async move {
        let server = axum::serve(listener, router).with_graceful_shutdown(async {
            let _ = shutdown_rx.await;
        });
        if let Err(err) = server.await {
            log::error!("MCP endpoint stopped: {err}");
        }
    });
    log::info!("MCP endpoint listening on http://127.0.0.1:{port}{PATH}");
    Ok(())
}

#[tauri::command]
pub fn mcp_stop(state: tauri::State<'_, McpState>) {
    stop(&state);
}

#[tauri::command]
pub fn mcp_respond(state: tauri::State<'_, McpState>, id: u64, body: Option<String>) {
    if let Some(sender) = state.pending.lock().unwrap().remove(&id) {
        let _ = sender.send(body);
    }
}

fn stop(state: &McpState) {
    if let Some(shutdown) = state.running.lock().unwrap().take() {
        let _ = shutdown.send(());
    }
    // Requests still waiting on the web app get dropped senders, so they answer 503.
    state.pending.lock().unwrap().clear();
}

/// The previous server may take a moment to release the port after a restart.
async fn bind(port: u16) -> Result<tokio::net::TcpListener, String> {
    let mut last_err = None;
    for _ in 0..10 {
        match tokio::net::TcpListener::bind(("127.0.0.1", port)).await {
            Ok(listener) => return Ok(listener),
            Err(err) => {
                last_err = Some(err);
                tokio::time::sleep(Duration::from_millis(100)).await;
            }
        }
    }
    let err = last_err.expect("bind attempted");
    Err(if err.kind() == std::io::ErrorKind::AddrInUse {
        format!("Port {port} is already in use by another app")
    } else {
        format!("Couldn't listen on port {port}: {err}")
    })
}

async fn handle(State(ctx): State<Arc<Ctx>>, headers: HeaderMap, body: Bytes) -> Response {
    // DNS-rebinding defence: only loopback Host names, and no cross-site browser origins.
    if !host_allowed(&headers, ctx.port) || !origin_allowed(&headers) {
        return (StatusCode::FORBIDDEN, "Forbidden").into_response();
    }
    if !authorized(&headers, &ctx.token) {
        return (
            StatusCode::UNAUTHORIZED,
            [(header::WWW_AUTHENTICATE, "Bearer")],
            "Missing or invalid bearer token",
        )
            .into_response();
    }
    let Ok(body) = String::from_utf8(body.to_vec()) else {
        return (StatusCode::BAD_REQUEST, "Body must be UTF-8 JSON").into_response();
    };

    let id = ctx.next_id.fetch_add(1, Ordering::Relaxed);
    let (tx, rx) = oneshot::channel();
    ctx.pending.lock().unwrap().insert(id, tx);
    if let Err(err) = ctx.app.emit("mcp-request", McpRequest { id, body }) {
        ctx.pending.lock().unwrap().remove(&id);
        log::error!("MCP request could not reach the app: {err}");
        return (StatusCode::SERVICE_UNAVAILABLE, "Shilling isn't ready").into_response();
    }

    match tokio::time::timeout(REQUEST_TIMEOUT, rx).await {
        Ok(Ok(Some(response))) => (
            StatusCode::OK,
            [(header::CONTENT_TYPE, "application/json")],
            response,
        )
            .into_response(),
        Ok(Ok(None)) => StatusCode::ACCEPTED.into_response(),
        Ok(Err(_)) => (StatusCode::SERVICE_UNAVAILABLE, "Assistant access was turned off").into_response(),
        Err(_) => {
            ctx.pending.lock().unwrap().remove(&id);
            (StatusCode::GATEWAY_TIMEOUT, "Shilling didn't respond in time").into_response()
        }
    }
}

fn authorized(headers: &HeaderMap, token: &str) -> bool {
    let Some(value) = headers.get(header::AUTHORIZATION).and_then(|v| v.to_str().ok()) else {
        return false;
    };
    let Some(presented) = value
        .strip_prefix("Bearer ")
        .or_else(|| value.strip_prefix("bearer "))
    else {
        return false;
    };
    presented.trim().as_bytes().ct_eq(token.as_bytes()).into()
}

fn host_allowed(headers: &HeaderMap, port: u16) -> bool {
    let Some(host) = headers.get(header::HOST).and_then(|v| v.to_str().ok()) else {
        return false;
    };
    ["127.0.0.1", "localhost", "[::1]"]
        .iter()
        .any(|name| host == *name || host == format!("{name}:{port}"))
}

fn origin_allowed(headers: &HeaderMap) -> bool {
    let Some(origin) = headers.get(header::ORIGIN).and_then(|v| v.to_str().ok()) else {
        return true; // Native clients don't send Origin.
    };
    let Some(rest) = origin
        .strip_prefix("http://")
        .or_else(|| origin.strip_prefix("https://"))
    else {
        return false;
    };
    let host = if rest.starts_with('[') {
        rest.split_inclusive(']').next().unwrap_or(rest)
    } else {
        rest.split(':').next().unwrap_or(rest)
    };
    matches!(host, "127.0.0.1" | "localhost" | "[::1]")
}

#[cfg(test)]
mod tests {
    use super::*;
    use axum::http::HeaderValue;

    fn headers(pairs: &[(header::HeaderName, &str)]) -> HeaderMap {
        let mut map = HeaderMap::new();
        for (name, value) in pairs {
            map.insert(name.clone(), HeaderValue::from_str(value).unwrap());
        }
        map
    }

    #[test]
    fn bearer_token_must_match() {
        let token = "a".repeat(64);
        assert!(authorized(&headers(&[(header::AUTHORIZATION, &format!("Bearer {token}"))]), &token));
        assert!(!authorized(&headers(&[(header::AUTHORIZATION, "Bearer nope")]), &token));
        assert!(!authorized(&headers(&[(header::AUTHORIZATION, &token)]), &token));
        assert!(!authorized(&HeaderMap::new(), &token));
    }

    #[test]
    fn only_loopback_hosts() {
        assert!(host_allowed(&headers(&[(header::HOST, "127.0.0.1:47615")]), 47615));
        assert!(host_allowed(&headers(&[(header::HOST, "localhost:47615")]), 47615));
        assert!(!host_allowed(&headers(&[(header::HOST, "evil.example:47615")]), 47615));
        assert!(!host_allowed(&headers(&[(header::HOST, "127.0.0.1:1")]), 47615));
        assert!(!host_allowed(&HeaderMap::new(), 47615));
    }

    #[test]
    fn rejects_cross_site_origins() {
        assert!(origin_allowed(&HeaderMap::new()));
        assert!(origin_allowed(&headers(&[(header::ORIGIN, "http://localhost:3000")])));
        assert!(origin_allowed(&headers(&[(header::ORIGIN, "http://[::1]:3000")])));
        assert!(!origin_allowed(&headers(&[(header::ORIGIN, "https://evil.example")])));
        assert!(!origin_allowed(&headers(&[(header::ORIGIN, "http://localhost.evil.example")])));
        assert!(!origin_allowed(&headers(&[(header::ORIGIN, "null")])));
    }

    #[test]
    fn busy_port_reports_in_use() {
        let held = std::net::TcpListener::bind(("127.0.0.1", 0)).unwrap();
        let port = held.local_addr().unwrap().port();
        let rt = tokio::runtime::Builder::new_current_thread().enable_all().build().unwrap();
        let err = rt.block_on(bind(port)).unwrap_err();
        assert_eq!(err, format!("Port {port} is already in use by another app"));

        drop(held);
        let listener = rt.block_on(bind(port)).expect("freed port binds");
        assert_eq!(listener.local_addr().unwrap().port(), port);
    }
}
