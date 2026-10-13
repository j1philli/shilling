fn main() {
    println!("cargo:rerun-if-changed=tauri.conf.json");
    println!("cargo:rerun-if-changed=icons/icon.icns");
    println!("cargo:rerun-if-changed=icons/icon.ico");
    println!("cargo:rerun-if-changed=icons/icon.png");
    println!("cargo:rerun-if-changed=desktop-build.txt");
    let build = std::fs::read_to_string("desktop-build.txt").unwrap_or_else(|_| "0".into());
    let build = build
        .trim()
        .parse::<u64>()
        .expect("desktop build must be numeric");
    println!("cargo:rustc-env=SHILLING_DESKTOP_BUILD={build}");
    tauri_build::build()
}
