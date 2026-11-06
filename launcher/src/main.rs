#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

mod code;
mod fetch;
mod install;
mod launch;
mod vhs;

use std::cell::RefCell;
use std::path::PathBuf;
use std::process::{Child, Command};
use std::rc::Rc;
use std::sync::Arc;
use std::sync::atomic::Ordering;
use std::thread::{self, JoinHandle};
use std::time::{Duration, Instant};

use fetch::{Error, Progress};
use slint::{CloseRequestResponse, ComponentHandle, PhysicalPosition, Timer, TimerMode, Weak};

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
    let root = home.clone();
    let slot = job.clone();
    app.on_install(move || {
        let Some(app) = weak.upgrade() else { return };
        if slot.borrow().is_some() {
            return;
        }
        match root.clone() {
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

    let weak = app.as_weak();
    let root = home.clone();
    app.on_play(move |nick| {
        let Some(app) = weak.upgrade() else {
            return Launch::Failed;
        };
        if !launch::valid_nick(&nick) {
            return Launch::Nick;
        }
        let Some(home) = &root else {
            return Launch::Failed;
        };
        match launch::start(home, &nick) {
            Ok(game) => {
                if let Err(error) = app.hide() {
                    eprintln!("failed to hide the launcher: {error}");
                }
                watch(game, app.as_weak());
                Launch::Started
            }
            Err(error) => {
                eprintln!("failed to start the game: {error}");
                Launch::Failed
            }
        }
    });

    let root = home.clone();
    app.on_open(move |place| {
        let Some(home) = &root else { return false };
        let logs = launch::instance(home).join("logs");
        let path = match place {
            Place::Logs => logs,
            Place::Screenshots => launch::instance(home).join("screenshots"),
            Place::LatestLog => logs.join("latest.log"),
        };
        path.exists() && reveal(&path)
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

    app.on_quit(quit);
    app.window().on_close_requested(|| {
        quit();
        CloseRequestResponse::HideWindow
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
        if app.window().is_visible() && !app.window().is_minimized() {
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

    app.show()?;
    slint::run_event_loop_until_quit()
}

fn quit() {
    if let Err(error) = slint::quit_event_loop() {
        eprintln!("failed to stop the launcher: {error}");
    }
}

fn watch(mut game: Child, weak: Weak<AppWindow>) {
    thread::spawn(move || {
        let crashed = !game.wait().is_ok_and(|status| status.success());
        let returned = slint::invoke_from_event_loop(move || {
            let Some(app) = weak.upgrade() else { return };
            app.set_crashed(crashed);
            if let Err(error) = app.show() {
                eprintln!("failed to show the launcher: {error}");
            }
        });
        if let Err(error) = returned {
            eprintln!("failed to return to the launcher: {error}");
        }
    });
}

fn reveal(path: &std::path::Path) -> bool {
    match Command::new("explorer").arg(path).spawn() {
        Ok(_) => true,
        Err(error) => {
            eprintln!("failed to open {}: {error}", path.display());
            false
        }
    }
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
