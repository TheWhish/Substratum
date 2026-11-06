#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

mod code;
mod fetch;
mod install;
mod vhs;

use std::cell::RefCell;
use std::path::PathBuf;
use std::rc::Rc;
use std::sync::Arc;
use std::sync::atomic::Ordering;
use std::thread::{self, JoinHandle};
use std::time::{Duration, Instant};

use fetch::{Error, Progress};
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

    let home = install::home();
    app.set_build(
        home.as_deref()
            .and_then(install::installed)
            .unwrap_or_default()
            .into(),
    );
    let job: Rc<RefCell<Option<Job>>> = Rc::default();

    let weak = app.as_weak();
    let slot = job.clone();
    app.on_install(move || {
        let Some(app) = weak.upgrade() else { return };
        if slot.borrow().is_some() {
            return;
        }
        match home.clone() {
            Some(home) => {
                *slot.borrow_mut() = Some(Job::start(home));
                app.set_preparing(true);
                app.set_progress(0.0);
                app.set_speed(0.0);
                app.set_downloading(false);
                app.set_busy(true);
            }
            None => app.invoke_finished(Outcome::Disk),
        }
    });

    let slot = job.clone();
    app.on_cancel(move || {
        if let Some(job) = slot.borrow().as_ref() {
            job.progress.cancel.store(true, Ordering::Relaxed);
        }
    });

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
        let Some(app) = weak.upgrade() else { return };
        if !app.window().is_minimized() {
            tape.advance(&app);
        }
        let finished = job
            .borrow_mut()
            .as_mut()
            .is_some_and(|job| job.report(&app));
        if finished && let Some(job) = job.borrow_mut().take() {
            app.set_busy(false);
            match job.worker.join() {
                Ok(Ok(build)) => {
                    app.set_build(build.into());
                    app.invoke_finished(Outcome::Done);
                }
                Ok(Err(Error::Cancelled)) => app.invoke_finished(Outcome::Cancelled),
                Ok(Err(error)) => {
                    eprintln!("installation failed: {error}");
                    app.invoke_finished(outcome(&error));
                }
                Err(_) => app.invoke_finished(Outcome::Failed),
            }
        }
    });

    app.run()
}

const SPEED_WINDOW: Duration = Duration::from_secs(1);

struct Job {
    progress: Arc<Progress>,
    worker: JoinHandle<fetch::Result<String>>,
    sampled: Instant,
    received: u64,
}

impl Job {
    fn start(home: PathBuf) -> Job {
        let progress = Arc::new(Progress::default());
        let shared = progress.clone();
        Job {
            progress,
            worker: thread::spawn(move || install::run(&home, &shared)),
            sampled: Instant::now(),
            received: 0,
        }
    }

    fn report(&mut self, app: &AppWindow) -> bool {
        let total = self.progress.total.load(Ordering::Relaxed);
        let done = self.progress.done.load(Ordering::Relaxed);
        app.set_preparing(total == 0);
        if total > 0 {
            app.set_progress((done as f64 / total as f64) as f32);
        }
        app.set_downloading(self.progress.received.load(Ordering::Relaxed) > 0);
        let elapsed = self.sampled.elapsed();
        if elapsed >= SPEED_WINDOW {
            let received = self.progress.received.load(Ordering::Relaxed);
            let megabytes = (received - self.received) as f64 / 1_000_000.0;
            app.set_speed((megabytes / elapsed.as_secs_f64()) as f32);
            self.sampled = Instant::now();
            self.received = received;
        }
        self.worker.is_finished()
    }
}

fn outcome(error: &Error) -> Outcome {
    match error {
        Error::Network { .. } => Outcome::Network,
        Error::Io { .. } => Outcome::Disk,
        Error::Corrupt { .. } => Outcome::Corrupt,
        Error::Invalid(_) => Outcome::Failed,
        Error::Cancelled => Outcome::Cancelled,
    }
}
