#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

mod code;
mod fetch;
mod install;
mod launch;
mod profile;
mod skin;
mod vhs;

use std::cell::RefCell;
use std::path::{Path, PathBuf};
use std::process::{Child, Command};
use std::rc::Rc;
use std::sync::Arc;
use std::sync::atomic::Ordering;
use std::thread::{self, JoinHandle};
use std::time::{Duration, Instant};

use fetch::{Error, Progress};
use profile::Profile;
use skin::{PRESETS, Skin};
use slint::{
    CloseRequestResponse, ComponentHandle, ModelRc, PhysicalPosition, Timer, TimerMode, VecModel,
    Weak,
};

slint::include_modules!();

const SPEED_WINDOW: Duration = Duration::from_secs(1);

fn main() -> Result<(), Box<dyn std::error::Error>> {
    let home: Rc<Path> = install::home().ok_or("APPDATA is not set")?.into();
    let profile = Rc::new(RefCell::new(load_profile(&home)?));

    let app = AppWindow::new()?;
    app.set_version(env!("CARGO_PKG_VERSION").into());
    app.set_scanlines(vhs::scanlines());
    app.set_tracking_time(vhs::TRACKING.as_millis() as i64);
    app.set_build(install::installed(&home).unwrap_or_default().into());
    app.set_presets(presets());
    app.set_nick(profile.borrow().nick.as_str().into());
    show_skin(&app, &home, &profile.borrow());

    let team_code = code::generate(&mut vhs::Rng::seeded());
    app.set_team_code(team_code.as_str().into());
    app.set_team_cells(code::cells(&team_code));
    app.set_join_cells(code::cells(""));

    bind_profile(&app, &home, &profile);
    let job: Rc<RefCell<Option<Job>>> = Rc::default();

    let weak = app.as_weak();
    let (root, slot) = (home.clone(), job.clone());
    app.on_install(move || {
        let Some(app) = weak.upgrade() else { return };
        if slot.borrow().is_some() {
            return;
        }
        *slot.borrow_mut() = Some(Job::start(root.to_path_buf()));
        app.set_preparing(true);
        app.set_progress(0.0);
        app.set_speed(0.0);
        app.set_downloading(false);
        app.set_busy(true);
    });

    let weak = app.as_weak();
    let (root, player) = (home.clone(), profile.clone());
    app.on_play(move || {
        let Some(app) = weak.upgrade() else {
            return Launch::Failed;
        };
        let profile = player.borrow();
        if !profile::valid_nick(&profile.nick) {
            return Launch::Nick;
        }
        match launch::start(&root, &profile) {
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
        let logs = launch::instance(&root).join("logs");
        let path = match place {
            Place::Logs => logs,
            Place::Screenshots => launch::instance(&root).join("screenshots"),
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
    slint::run_event_loop_until_quit()?;
    Ok(())
}

fn load_profile(home: &Path) -> fetch::Result<Profile> {
    Profile::load(home).or_else(|error| {
        eprintln!("profile is unreadable, starting a new one: {error}");
        Profile::reset(home)
    })
}

fn bind_profile(app: &AppWindow, home: &Rc<Path>, profile: &Rc<RefCell<Profile>>) {
    let weak = app.as_weak();
    let (root, player) = (home.clone(), profile.clone());
    app.on_nick_edited(move |text| {
        let Some(app) = weak.upgrade() else { return };
        let clean = profile::sanitize_nick(&text);
        if clean != text.as_str() {
            app.set_nick(clean.as_str().into());
        }
        let mut profile = player.borrow_mut();
        profile.nick = clean;
        persist(&root, &profile);
    });

    let weak = app.as_weak();
    let (root, player) = (home.clone(), profile.clone());
    app.on_select_skin(move |index| {
        let Some(app) = weak.upgrade() else { return };
        let mut profile = player.borrow_mut();
        match usize::try_from(index) {
            Ok(index) if index < PRESETS.len() => profile.choose_preset(index),
            _ if profile.choose_custom(&root) => {}
            _ => return,
        }
        persist(&root, &profile);
        show_skin(&app, &root, &profile);
    });

    let weak = app.as_weak();
    let (root, player) = (home.clone(), profile.clone());
    app.on_pick_skin(move || {
        let Some(app) = weak.upgrade() else {
            return Pick::Cancelled;
        };
        let Some(path) = rfd::FileDialog::new()
            .add_filter("PNG", &["png"])
            .set_parent(&app.window().window_handle())
            .pick_file()
        else {
            return Pick::Cancelled;
        };
        let skin = match Skin::read(&path) {
            Ok(skin) => skin,
            Err(error) => {
                eprintln!("rejected skin: {error}");
                return Pick::Rejected;
            }
        };
        let mut profile = player.borrow_mut();
        if let Err(error) = profile.adopt_custom(&root, &skin) {
            eprintln!("failed to store the skin: {error}");
            return Pick::Failed;
        }
        persist(&root, &profile);
        show_skin(&app, &root, &profile);
        Pick::Chosen
    });
}

fn persist(home: &Path, profile: &Profile) {
    if let Err(error) = profile.save(home) {
        eprintln!("failed to save the profile: {error}");
    }
}

fn show_skin(app: &AppWindow, home: &Path, profile: &Profile) {
    let skin = profile.skin(home).or_else(|error| {
        eprintln!("failed to read the skin, showing the default one: {error}");
        Skin::preset(&PRESETS[0])
    });
    match skin {
        Ok(skin) => {
            app.set_front(skin.front());
            app.set_back(skin.back());
            app.set_face(skin.face());
        }
        Err(error) => eprintln!("built-in skin is broken: {error}"),
    }
    match Profile::custom_skin(home) {
        Ok(custom) => {
            app.set_custom_skin(custom.is_some());
            app.set_custom_front(custom.map(|skin| skin.front()).unwrap_or_default());
        }
        Err(error) => {
            eprintln!("failed to read the custom skin: {error}");
            app.set_custom_skin(false);
        }
    }
    app.set_skin(profile.preset().unwrap_or(PRESETS.len()) as i32);
}

fn presets() -> ModelRc<slint::Image> {
    let figures: Vec<slint::Image> = PRESETS
        .iter()
        .filter_map(|preset| match Skin::preset(preset) {
            Ok(skin) => Some(skin.front()),
            Err(error) => {
                eprintln!("built-in skin is broken: {error}");
                None
            }
        })
        .collect();
    ModelRc::new(VecModel::from(figures))
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

fn reveal(path: &Path) -> bool {
    match Command::new("explorer").arg(path).spawn() {
        Ok(_) => true,
        Err(error) => {
            eprintln!("failed to open {}: {error}", path.display());
            false
        }
    }
}

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
