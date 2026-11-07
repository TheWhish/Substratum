#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

mod code;
mod direct;
mod fetch;
mod install;
mod launch;
mod profile;
mod settings;
mod skin;
mod team;
#[cfg(test)]
mod translations;
mod update;
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
use install::Ready;
use profile::Profile;
use settings::{AfterLaunch, Language, Settings, Window};
use skin::{PRESETS, Skin};
use slint::{
    CloseRequestResponse, ComponentHandle, Model, ModelRc, PhysicalPosition, Timer, TimerMode,
    VecModel, Weak,
};
use substratum_protocol::{Member, Presence, Refusal, Team, valid_nick};

slint::include_modules!();

const SPEED_WINDOW: Duration = Duration::from_secs(1);
const FALLBACK_MEMORY: u32 = 4;

fn main() -> Result<(), Box<dyn std::error::Error>> {
    update::tidy();
    let home: Rc<Path> = install::home().ok_or("APPDATA is not set")?.into();
    let profile = Rc::new(RefCell::new(load_profile(&home)?));
    let settings = Rc::new(RefCell::new(load_settings(&home)));
    let total = settings::total_memory().unwrap_or_else(|| {
        eprintln!("failed to read the installed memory, assuming {FALLBACK_MEMORY} GB");
        FALLBACK_MEMORY
    });

    let app = AppWindow::new()?;
    app.set_version(env!("CARGO_PKG_VERSION").into());
    app.set_scanlines(vhs::scanlines());
    app.set_tracking_time(vhs::TRACKING.as_millis() as i64);
    app.set_build(install::installed(&home).unwrap_or_default().into());
    app.set_presets(presets());
    app.set_nick(profile.borrow().nick.as_str().into());
    show_skin(&app, &profile.borrow());

    app.set_join_cells(code::cells(""));

    let team = start_team(&app, &profile.borrow())?;
    bind_team(&app, &team);
    bind_profile(&app, &home, &profile, &team);
    bind_settings(&app, &home, &settings, total);
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
    let (root, player, options, link) = (home.clone(), profile.clone(), settings.clone(), team.link.clone());
    app.on_play(move || {
        let Some(app) = weak.upgrade() else {
            return Launch::Failed;
        };
        let profile = player.borrow();
        if !valid_nick(&profile.nick) {
            return Launch::Nick;
        }
        let options = *options.borrow();
        match launch::start(&root, &profile, &options, options.heap(total), &link) {
            Ok(game) => {
                if options.after_launch == AfterLaunch::Minimize {
                    app.window().set_minimized(true);
                }
                app.set_playing(true);
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
            Place::Home => root.to_path_buf(),
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
    let weak = app.as_weak();
    app.window().on_close_requested(move || {
        if let Some(app) = weak.upgrade() {
            app.invoke_leave();
        }
        CloseRequestResponse::KeepWindowShown
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

    let (player, commands) = (profile.clone(), team.clone());
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
                Ok(Ok(Ready::Build(build))) => {
                    app.set_build(build.into());
                    commands.send(team::Command::Profile(team_profile(&app, &player.borrow())));
                    app.invoke_finished(Outcome::Done);
                }
                Ok(Ok(Ready::Launcher(fresh))) => match update::apply(&fresh) {
                    Ok(()) => quit(),
                    Err(error) => {
                        eprintln!("failed to replace the launcher: {error}");
                        app.invoke_finished(Outcome::Failed);
                    }
                },
                Ok(Err(Error::Cancelled)) => app.invoke_finished(Outcome::Cancelled),
                Ok(Err(error)) => {
                    eprintln!("installation failed: {error}");
                    app.invoke_finished(outcome(&error));
                }
                Err(_) => app.invoke_finished(Outcome::Failed),
            }
        }
    });

    check_updates(home.to_path_buf(), app.as_weak());
    app.show()?;
    slint::run_event_loop_until_quit()?;
    Ok(())
}

fn check_updates(home: PathBuf, weak: Weak<AppWindow>) {
    thread::spawn(move || match install::pending(&home) {
        Ok(false) => {}
        Ok(true) => {
            let started = slint::invoke_from_event_loop(move || {
                if let Some(app) = weak.upgrade()
                    && !app.get_playing()
                {
                    app.invoke_install();
                }
            });
            if let Err(error) = started {
                eprintln!("failed to start the update: {error}");
            }
        }
        Err(error) => eprintln!("update check failed: {error}"),
    });
}

fn load_profile(home: &Path) -> fetch::Result<Profile> {
    Profile::load(home).or_else(|error| {
        eprintln!("profile is unreadable, starting a new one: {error}");
        Profile::reset(home)
    })
}

fn bind_profile(
    app: &AppWindow,
    home: &Rc<Path>,
    profile: &Rc<RefCell<Profile>>,
    team: &team::Handle,
) {
    let weak = app.as_weak();
    let (root, player, commands) = (home.clone(), profile.clone(), team.clone());
    app.on_nick_edited(move |text| {
        let Some(app) = weak.upgrade() else { return };
        let clean = profile::sanitize_nick(&text);
        if clean != text.as_str() {
            app.set_nick(clean.as_str().into());
        }
        let mut profile = player.borrow_mut();
        profile.nick = clean;
        persist(&root, &profile);
        if valid_nick(&profile.nick) {
            commands.send(team::Command::Profile(team_profile(&app, &profile)));
        }
    });

    let weak = app.as_weak();
    let (root, player, commands) = (home.clone(), profile.clone(), team.clone());
    app.on_select_skin(move |index| {
        let Some(app) = weak.upgrade() else { return };
        let mut profile = player.borrow_mut();
        match usize::try_from(index) {
            Ok(index) if index < PRESETS.len() => profile.choose_preset(index),
            _ => return,
        }
        persist(&root, &profile);
        show_skin(&app, &profile);
        if valid_nick(&profile.nick) {
            commands.send(team::Command::Profile(team_profile(&app, &profile)));
        }
    });
}

fn start_team(app: &AppWindow, profile: &Profile) -> Result<team::Handle, Box<dyn std::error::Error>> {
    let weak = app.as_weak();
    let handle = team::start(profile.signing_key()?, team_profile(app, profile), move |update| {
        let weak = weak.clone();
        let shown = slint::invoke_from_event_loop(move || {
            if let Some(app) = weak.upgrade() {
                show_team(&app, update);
            }
        });
        if let Err(error) = shown {
            eprintln!("failed to show the team: {error}");
        }
    })?;
    show_team(
        app,
        team::Update {
            online: false,
            me: String::new(),
            team: None,
            refused: None,
            direct: None,
        },
    );
    Ok(handle)
}

fn bind_team(app: &AppWindow, team: &team::Handle) {
    let commands = team.clone();
    app.on_join_team(move |code| commands.send(team::Command::Join(code::sanitize(&code))));
    let commands = team.clone();
    app.on_leave_team(move || commands.send(team::Command::Leave));
    let (weak, commands) = (app.as_weak(), team.clone());
    app.on_kick(move |index| {
        let Some(app) = weak.upgrade() else { return };
        if let Some(slot) = app.get_slots().row_data(index as usize) {
            commands.send(team::Command::Kick(slot.uuid.into()));
        }
    });
}

fn team_profile(app: &AppWindow, profile: &Profile) -> team::Profile {
    team::Profile {
        nick: profile.nick.clone(),
        skin: profile.skin.clone(),
        build: app.get_build().into(),
    }
}

fn show_team(app: &AppWindow, update: team::Update) {
    let code = update.team.as_ref().map_or("", |team| team.code.as_str());
    app.set_team_online(update.online);
    app.set_team_route(match update.direct {
        Some(true) => Route::Direct,
        Some(false) => Route::Relay,
        None => Route::None,
    });
    app.set_team_code(code.into());
    app.set_team_cells(code::cells(code));
    let slots = match &update.team {
        Some(team) => team_slots(team, &update.me),
        None => vec![Slot {
            nick: app.get_nick(),
            face: face(PRESETS.get(app.get_skin() as usize).map_or("", |preset| preset.name)),
            host: true,
            ..Slot::default()
        }],
    };
    app.set_slots(ModelRc::new(VecModel::from(slots)));
    if let Some(reason) = update.refused.and_then(rejection) {
        app.invoke_rejected(reason);
    }
}

fn team_slots(team: &Team, me: &str) -> Vec<Slot> {
    let build = team.member(&team.host).map(|host| host.build.as_str());
    let leading = team.host == me && team.session.is_none();
    team.members
        .iter()
        .map(|member| Slot {
            uuid: member.uuid.as_str().into(),
            nick: member.nick.as_str().into(),
            face: face(&member.skin),
            host: member.uuid == team.host,
            state: slot_state(member, build),
            kickable: leading && member.uuid != me,
        })
        .collect()
}

fn slot_state(member: &Member, build: Option<&str>) -> i32 {
    if !member.online {
        return 4;
    }
    if build.is_some_and(|build| build != member.build) {
        return 5;
    }
    match member.presence {
        Presence::Away => 0,
        Presence::Menu => 1,
        Presence::Ready => 2,
        Presence::Playing => 3,
    }
}

fn face(skin: &str) -> slint::Image {
    PRESETS
        .iter()
        .find(|preset| preset.name == skin)
        .and_then(|preset| Skin::preset(preset).ok())
        .map(|skin| skin.face())
        .unwrap_or_default()
}

fn rejection(reason: Refusal) -> Option<Rejection> {
    match reason {
        Refusal::Unknown => Some(Rejection::Unknown),
        Refusal::Full => Some(Rejection::Full),
        Refusal::Locked => Some(Rejection::Locked),
        Refusal::NickTaken => Some(Rejection::NickTaken),
        Refusal::NotReady => None,
        Refusal::NotHost | Refusal::Invalid => Some(Rejection::Failed),
    }
}

fn load_settings(home: &Path) -> Settings {
    Settings::load(home).unwrap_or_else(|error| {
        eprintln!("settings are unreadable, using defaults: {error}");
        Settings::default()
    })
}

fn bind_settings(app: &AppWindow, home: &Rc<Path>, settings: &Rc<RefCell<Settings>>, total: u32) {
    show_settings(app, &settings.borrow(), total);
    select_language(settings.borrow().language);

    let weak = app.as_weak();
    let (root, options) = (home.clone(), settings.clone());
    app.on_adjust(move |choice, delta| {
        let Some(app) = weak.upgrade() else { return };
        let mut settings = options.borrow_mut();
        match choice {
            Choice::Memory => settings.step_memory(total, delta),
            Choice::Window => {
                settings.window = settings::step(&Window::ALL, settings.window, delta)
            }
            Choice::Language => {
                settings.language = settings::step(&Language::ALL, settings.language, delta);
                select_language(settings.language);
            }
            Choice::AfterLaunch => {
                settings.after_launch =
                    settings::step(&AfterLaunch::ALL, settings.after_launch, delta)
            }
        }
        if let Err(error) = settings.save(&root) {
            eprintln!("failed to save the settings: {error}");
        }
        show_settings(&app, &settings, total);
    });
}

fn show_settings(app: &AppWindow, settings: &Settings, total: u32) {
    let index = |position: Option<usize>| position.unwrap_or(0) as i32;
    app.set_memory(settings.memory as i32);
    app.set_auto_memory(settings::auto_memory(total) as i32);
    app.set_window_mode(index(
        Window::ALL
            .iter()
            .position(|window| *window == settings.window),
    ));
    app.set_language(index(
        Language::ALL
            .iter()
            .position(|language| *language == settings.language),
    ));
    app.set_after_launch(index(
        AfterLaunch::ALL
            .iter()
            .position(|after| *after == settings.after_launch),
    ));
}

fn select_language(language: Language) {
    if let Err(error) = slint::select_bundled_translation(language.bundle()) {
        eprintln!("failed to switch the language: {error}");
    }
}

fn persist(home: &Path, profile: &Profile) {
    if let Err(error) = profile.save(home) {
        eprintln!("failed to save the profile: {error}");
    }
}

fn show_skin(app: &AppWindow, profile: &Profile) {
    match profile.skin() {
        Ok(skin) => {
            app.set_front(skin.front());
            app.set_back(skin.back());
        }
        Err(error) => eprintln!("built-in skin is broken: {error}"),
    }
    app.set_skin(profile.preset() as i32);
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
            app.set_playing(false);
            app.set_crashed(crashed);
            app.window().set_minimized(false);
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
    worker: JoinHandle<fetch::Result<Ready>>,
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
