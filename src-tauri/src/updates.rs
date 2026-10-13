use semver::Version;
use serde::Serialize;
use std::time::Duration;
use tauri::{AppHandle, Emitter, State};
use tauri_plugin_updater::{Update, UpdaterExt};
use tokio::sync::Mutex;

pub const BUILD: &str = env!("SHILLING_DESKTOP_BUILD");
const FEED: &str = "https://updates.shilling.finance";

#[derive(Default)]
pub struct Updates(pub Mutex<Option<Update>>);

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Status {
    version: String,
    build: String,
    supported: bool,
}

#[derive(Serialize)]
pub struct Available {
    version: String,
    notes: String,
}

fn supported() -> bool {
    !cfg!(debug_assertions)
        && BUILD != "0"
        && (!cfg!(target_os = "linux") || std::env::var_os("APPIMAGE").is_some())
}

pub fn newer(current: &Version, installed_build: u64, remote: &Version) -> bool {
    // Package versions stay shared with mobile. CI build identity orders repeated
    // desktop builds without rebuilding an artifact when promoting beta to stable.
    let product = |v: &Version| (v.major, v.minor, v.patch);
    let Some(build) = remote
        .build
        .as_str()
        .strip_prefix("build.")
        .and_then(|s| s.parse::<u64>().ok())
    else {
        return false;
    };
    remote.pre.is_empty()
        && build > 0
        && (product(remote) > product(current)
            || (product(remote) == product(current) && build > installed_build))
}

fn target() -> String {
    let os = if cfg!(target_os = "macos") {
        "darwin"
    } else {
        std::env::consts::OS
    };
    format!("{}-{}", os, std::env::consts::ARCH)
}

#[tauri::command]
pub fn desktop_update_status(app: AppHandle) -> Status {
    Status {
        version: app.package_info().version.to_string(),
        build: BUILD.into(),
        supported: supported(),
    }
}

#[tauri::command]
pub async fn desktop_update_check(
    app: AppHandle,
    state: State<'_, Updates>,
    channel: String,
) -> Result<Option<Available>, String> {
    if !matches!(channel.as_str(), "stable" | "beta") {
        return Err("Unknown update channel".into());
    }
    if !supported() {
        return Err(
            "Use an installed desktop release (AppImage on Linux) to check for updates.".into(),
        );
    }
    let mut pending = state
        .0
        .try_lock()
        .map_err(|_| "An update operation is already running")?;
    *pending = None;
    let url = format!("{}/{}/{}.json", FEED, channel, target())
        .parse()
        .map_err(|_| "Invalid update endpoint")?;
    let update = app
        .updater_builder()
        .endpoints(vec![url])
        .map_err(|e| e.to_string())?
        .timeout(Duration::from_secs(30))
        .version_comparator(|current, remote| {
            newer(&current, BUILD.parse().unwrap_or(0), &remote.version)
        })
        .build()
        .map_err(|e| e.to_string())?
        .check()
        .await
        .map_err(|e| e.to_string())?;
    // Only our immutable release assets are accepted, even if a feed is edited.
    if let Some(ref candidate) = update {
        let url = &candidate.download_url;
        if url.scheme() != "https"
            || url.host_str() != Some("github.com")
            || !url
                .path()
                .starts_with("/j1philli/shilling/releases/download/desktop-")
        {
            return Err("Unexpected update download location".into());
        }
    }
    let result = update.as_ref().map(|u| Available {
        version: u.version.clone(),
        notes: u.body.clone().unwrap_or_default(),
    });
    *pending = update;
    Ok(result)
}

#[tauri::command]
pub async fn desktop_update_install(
    app: AppHandle,
    state: State<'_, Updates>,
    version: String,
) -> Result<(), String> {
    let mut pending = state
        .0
        .try_lock()
        .map_err(|_| "An update operation is already running")?;
    let update = pending
        .as_ref()
        .filter(|u| u.version == version)
        .ok_or("Check for this update again before installing")?
        .clone();
    if !supported() {
        return Err("This package cannot update itself".into());
    }
    let progress_app = app.clone();
    let mut received = 0u64;
    update
        .download_and_install(
            move |chunk, total| {
                received += chunk as u64;
                let _ = progress_app.emit(
                    "desktop-update-progress",
                    serde_json::json!({"received": received, "total": total}),
                );
            },
            || {},
        )
        .await
        .map_err(|e| e.to_string())?;
    *pending = None;
    app.restart();
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn orders_builds_and_never_downgrades_product_or_build() {
        let current = Version::parse("0.1.1").unwrap();
        for (remote, expected) in [
            ("0.1.1+build.11", true),
            ("0.1.1+build.10", false),
            ("0.1.1+build.9", false),
            ("0.1.0+build.99", false),
            ("0.1.2+build.12", true),
            ("0.1.2", false),
            ("0.1.2-beta.1+build.12", false),
            ("0.1.1+build.bad", false),
        ] {
            assert_eq!(
                newer(&current, 10, &Version::parse(remote).unwrap()),
                expected,
                "{remote}"
            );
        }
    }
}
