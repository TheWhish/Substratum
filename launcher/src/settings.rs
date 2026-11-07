use std::fs;
use std::io;
use std::path::Path;

use serde::{Deserialize, Serialize};
use windows_sys::Win32::Globalization::GetUserDefaultUILanguage;
use windows_sys::Win32::System::SystemInformation::{GlobalMemoryStatusEx, MEMORYSTATUSEX};

use crate::fetch::{Error, Result};
use crate::install;

const FILE: &str = "launcher.toml";
const MIN_MEMORY: u32 = 2;
const MAX_AUTO_MEMORY: u32 = 4;
const RUSSIAN: u16 = 0x19;
const GIB: u64 = 1024 * 1024 * 1024;

#[derive(Clone, Copy, Default, PartialEq, Eq, Debug, Serialize, Deserialize)]
#[serde(rename_all = "kebab-case")]
pub enum Window {
    #[default]
    Fullscreen,
    Hd,
    HdPlus,
    FullHd,
}

impl Window {
    pub const ALL: [Window; 4] = [
        Window::Fullscreen,
        Window::Hd,
        Window::HdPlus,
        Window::FullHd,
    ];

    pub fn size(self) -> Option<(u32, u32)> {
        match self {
            Window::Fullscreen => None,
            Window::Hd => Some((1280, 720)),
            Window::HdPlus => Some((1600, 900)),
            Window::FullHd => Some((1920, 1080)),
        }
    }
}

#[derive(Clone, Copy, PartialEq, Eq, Debug, Serialize, Deserialize)]
#[serde(rename_all = "kebab-case")]
pub enum Language {
    Ru,
    En,
}

impl Language {
    pub const ALL: [Language; 2] = [Language::Ru, Language::En];

    pub fn system() -> Language {
        let primary = unsafe { GetUserDefaultUILanguage() } & 0x3ff;
        if primary == RUSSIAN {
            Language::Ru
        } else {
            Language::En
        }
    }

    pub fn bundle(self) -> &'static str {
        match self {
            Language::Ru => "",
            Language::En => "en_US",
        }
    }

    pub fn minecraft(self) -> &'static str {
        match self {
            Language::Ru => "ru_ru",
            Language::En => "en_us",
        }
    }
}

#[derive(Clone, Copy, Default, PartialEq, Eq, Debug, Serialize, Deserialize)]
#[serde(rename_all = "kebab-case")]
pub enum AfterLaunch {
    #[default]
    #[serde(alias = "hide", alias = "close")]
    Minimize,
    Keep,
}

impl AfterLaunch {
    pub const ALL: [AfterLaunch; 2] = [AfterLaunch::Minimize, AfterLaunch::Keep];
}

#[derive(Clone, Copy, PartialEq, Eq, Debug, Serialize, Deserialize)]
#[serde(default, rename_all = "kebab-case")]
pub struct Settings {
    pub memory: u32,
    pub window: Window,
    pub language: Language,
    pub after_launch: AfterLaunch,
}

impl Default for Settings {
    fn default() -> Settings {
        Settings {
            memory: 0,
            window: Window::default(),
            language: Language::system(),
            after_launch: AfterLaunch::default(),
        }
    }
}

impl Settings {
    pub fn load(home: &Path) -> Result<Settings> {
        let path = home.join(FILE);
        match fs::read_to_string(&path) {
            Ok(text) => toml::from_str(&text)
                .map_err(|error| Error::Invalid(format!("{}: {error}", path.display()))),
            Err(error) if error.kind() == io::ErrorKind::NotFound => Ok(Settings::default()),
            Err(error) => Err(Error::Io {
                path,
                source: error,
            }),
        }
    }

    pub fn save(&self, home: &Path) -> Result<()> {
        let text =
            toml::to_string(self).map_err(|error| Error::Invalid(format!("settings: {error}")))?;
        install::save(&home.join(FILE), text.as_bytes())
    }

    pub fn heap(&self, total: u32) -> u32 {
        match self.memory {
            0 => auto_memory(total),
            chosen => chosen.clamp(MIN_MEMORY, max_memory(total)),
        }
    }

    pub fn step_memory(&mut self, total: u32, delta: i32) {
        let choices: Vec<u32> = std::iter::once(0)
            .chain(MIN_MEMORY..=max_memory(total))
            .collect();
        self.memory = step(&choices, self.memory, delta);
    }
}

pub fn step<T: Copy + PartialEq>(all: &[T], current: T, delta: i32) -> T {
    let index = all.iter().position(|item| *item == current).unwrap_or(0) as i64;
    all[(index + delta as i64).rem_euclid(all.len() as i64) as usize]
}

pub fn auto_memory(total: u32) -> u32 {
    (total / 4).clamp(MIN_MEMORY, MAX_AUTO_MEMORY)
}

pub fn max_memory(total: u32) -> u32 {
    (total / 2).max(MIN_MEMORY)
}

pub fn total_memory() -> Option<u32> {
    let mut status = MEMORYSTATUSEX {
        dwLength: size_of::<MEMORYSTATUSEX>() as u32,
        ..unsafe { std::mem::zeroed() }
    };
    let read = unsafe { GlobalMemoryStatusEx(&mut status) } != 0;
    read.then(|| ((status.ullTotalPhys + GIB / 2) / GIB) as u32)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn memory_follows_installed_ram() {
        assert_eq!(auto_memory(8), 2);
        assert_eq!(auto_memory(16), 4);
        assert_eq!(auto_memory(64), 4);
        assert_eq!(max_memory(16), 8);
        assert_eq!(max_memory(2), 2);
        let mut settings = Settings {
            memory: 0,
            ..Settings::default()
        };
        settings.step_memory(8, -1);
        assert_eq!(settings.memory, 4);
        settings.step_memory(8, 1);
        assert_eq!(settings.memory, 0);
        settings.memory = 12;
        assert_eq!(settings.heap(8), 4);
    }

    #[test]
    fn steps_wrap_around() {
        assert_eq!(step(&Window::ALL, Window::FullHd, 1), Window::Fullscreen);
        assert_eq!(step(&Window::ALL, Window::Fullscreen, -1), Window::FullHd);
        assert_eq!(
            step(&AfterLaunch::ALL, AfterLaunch::Minimize, 3),
            AfterLaunch::Keep
        );
    }

    #[test]
    fn settings_round_trip_and_tolerate_missing_keys() {
        let settings = Settings {
            memory: 6,
            window: Window::HdPlus,
            language: Language::En,
            after_launch: AfterLaunch::Keep,
        };
        let text = toml::to_string(&settings).unwrap();
        assert_eq!(toml::from_str::<Settings>(&text).unwrap(), settings);
        let partial: Settings = toml::from_str("window = \"hd\"").unwrap();
        assert_eq!(partial.window, Window::Hd);
        assert_eq!(partial.memory, 0);
        for old in ["hide", "close"] {
            let migrated: Settings = toml::from_str(&format!("after-launch = \"{old}\"")).unwrap();
            assert_eq!(migrated.after_launch, AfterLaunch::Minimize);
        }
    }
}
