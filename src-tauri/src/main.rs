#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

use tauri::menu::{MenuBuilder, PredefinedMenuItem, SubmenuBuilder};
#[cfg(target_os = "macos")]
use tauri::Manager;
use tauri_plugin_log::{Target, TargetKind};

/// Gives the window an empty unified toolbar so macOS lays out the traffic lights and
/// window corners the way it does for native toolbar apps, instead of us positioning them.
/// The web app reserves the matching space (MAC_TITLE_STRIP_HEIGHT / MAC_NAV_RAIL_WIDTH in
/// the web app's Main.kt).
#[cfg(target_os = "macos")]
fn install_unified_toolbar(window: &tauri::WebviewWindow) -> tauri::Result<()> {
    use objc2::{MainThreadMarker, MainThreadOnly};
    use objc2_app_kit::{NSToolbar, NSWindow, NSWindowToolbarStyle};

    let Some(mtm) = MainThreadMarker::new() else {
        return Ok(());
    };
    let ns_window = window.ns_window()?.cast::<NSWindow>();
    // SAFETY: Tauri hands out a valid NSWindow pointer and setup runs on the main thread.
    let Some(ns_window) = (unsafe { ns_window.as_ref() }) else {
        return Ok(());
    };
    let toolbar = NSToolbar::init(NSToolbar::alloc(mtm));
    ns_window.setToolbar(Some(&toolbar));
    ns_window.setToolbarStyle(NSWindowToolbarStyle::Unified);
    Ok(())
}

fn main() {
    let mut builder = tauri::Builder::default()
        .plugin(tauri_plugin_shell::init())
        .plugin(
            tauri_plugin_log::Builder::new()
                .target(Target::new(TargetKind::Stdout))
                .build(),
        )
        .setup(|app| {
            let app_menu = SubmenuBuilder::new(app, "Shilling")
                .about(None)
                .separator()
                .services()
                .separator()
                .hide()
                .hide_others()
                .show_all()
                .separator()
                .quit()
                .build()?;

            let edit_menu = SubmenuBuilder::new(app, "Edit")
                .undo()
                .redo()
                .separator()
                .cut()
                .copy()
                .paste()
                .separator()
                .select_all()
                .build()?;

            let window_menu = SubmenuBuilder::new(app, "Window")
                .minimize()
                .item(&PredefinedMenuItem::maximize(app, None)?)
                .close_window()
                .build()?;

            let menu = MenuBuilder::new(app)
                .item(&app_menu)
                .item(&edit_menu)
                .item(&window_menu)
                .build()?;

            app.set_menu(menu)?;

            #[cfg(target_os = "macos")]
            if let Some(window) = app.get_webview_window("main") {
                install_unified_toolbar(&window)?;
            }

            Ok(())
        });

    #[cfg(debug_assertions)]
    {
        builder = builder.plugin(tauri_plugin_mcp::init_with_config(
            tauri_plugin_mcp::PluginConfig::new("Shilling".to_string())
                .start_socket_server(true)
                .socket_path("/tmp/tauri-mcp.sock".into()),
        ));
    }

    builder
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
