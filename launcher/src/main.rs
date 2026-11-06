#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

mod code;
mod vhs;

use slint::{PhysicalPosition, Timer, TimerMode};

slint::include_modules!();

fn main() -> Result<(), slint::PlatformError> {
    let app = AppWindow::new()?;
    app.set_version(env!("CARGO_PKG_VERSION").into());
    app.set_scanlines(vhs::scanlines());
    app.set_tracking_time(vhs::TRACKING.as_millis() as i64);

    let team_code = code::generate(&mut vhs::Rng::seeded());
    app.set_team_code(team_code.as_str().into());
    app.set_team_cells(code::cells(&team_code));
    app.set_join_cells(code::cells(""));

    let weak = app.as_weak();
    app.on_join_edited(move |text| {
        let Some(app) = weak.upgrade() else { return };
        let clean = code::sanitize(&text);
        app.set_join_cells(code::cells(&clean));
        if clean != text.as_str() {
            app.set_join_text(clean.into());
        }
    });

    let weak = app.as_weak();
    app.on_quit(move || {
        if let Some(app) = weak.upgrade()
            && let Err(error) = app.hide()
        {
            eprintln!("failed to close the launcher window: {error}");
        }
    });

    let weak = app.as_weak();
    app.on_drag_window(move |dx, dy| {
        let Some(app) = weak.upgrade() else { return };
        let window = app.window();
        let scale = window.scale_factor();
        let position = window.position();
        window.set_position(PhysicalPosition::new(
            position.x + (dx * scale).round() as i32,
            position.y + (dy * scale).round() as i32,
        ));
    });

    let mut tape = vhs::Tape::new();
    let weak = app.as_weak();
    let ticker = Timer::default();
    ticker.start(TimerMode::Repeated, vhs::FRAME, move || {
        if let Some(app) = weak.upgrade()
            && !app.window().is_minimized()
        {
            tape.advance(&app);
        }
    });

    app.run()
}
