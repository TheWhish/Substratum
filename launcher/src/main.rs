#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

slint::include_modules!();

fn main() -> Result<(), slint::PlatformError> {
    let window = AppWindow::new()?;
    let weak = window.as_weak();
    window.on_play(move || {
        let Some(window) = weak.upgrade() else { return };
        window.set_status("Установка игры появится на следующем шаге".into());
    });
    window.run()
}
