use std::fs;
use std::io;
use std::path::Path;

use ed25519_dalek::SigningKey;
use serde::{Deserialize, Serialize};

use substratum_protocol::NICK_MAX;

use crate::fetch::{Error, Result, hex, io_error};
use crate::install;
use crate::skin::{PRESETS, Skin};

const FILE: &str = "profile.toml";
const BROKEN: &str = "profile.broken.toml";
const DEFAULT_NICK: &str = "PLAYER";

#[derive(Serialize, Deserialize)]
pub struct Profile {
    pub nick: String,
    pub skin: String,
    key: String,
}

impl Profile {
    pub fn load(home: &Path) -> Result<Profile> {
        let path = home.join(FILE);
        let text = match fs::read_to_string(&path) {
            Ok(text) => text,
            Err(error) if error.kind() == io::ErrorKind::NotFound => {
                let profile = Profile::create()?;
                profile.save(home)?;
                return Ok(profile);
            }
            Err(error) => {
                return Err(Error::Io {
                    path,
                    source: error,
                });
            }
        };
        let mut profile: Profile = toml::from_str(&text)
            .map_err(|error| Error::Invalid(format!("{}: {error}", path.display())))?;
        profile.signing_key()?;
        profile.nick = sanitize_nick(&profile.nick);
        if !PRESETS.iter().any(|preset| preset.name == profile.skin) {
            profile.skin = PRESETS[0].name.to_owned();
        }
        Ok(profile)
    }

    pub fn reset(home: &Path) -> Result<Profile> {
        let path = home.join(FILE);
        if path.exists() {
            let broken = home.join(BROKEN);
            fs::rename(&path, &broken).map_err(io_error(&broken))?;
        }
        let profile = Profile::create()?;
        profile.save(home)?;
        Ok(profile)
    }

    fn create() -> Result<Profile> {
        let mut seed = [0; 32];
        getrandom::fill(&mut seed)
            .map_err(|error| Error::Invalid(format!("no system randomness: {error}")))?;
        Ok(Profile {
            nick: DEFAULT_NICK.to_owned(),
            skin: PRESETS[0].name.to_owned(),
            key: hex(&seed),
        })
    }

    pub fn save(&self, home: &Path) -> Result<()> {
        let text =
            toml::to_string(self).map_err(|error| Error::Invalid(format!("profile: {error}")))?;
        install::save(&home.join(FILE), text.as_bytes())
    }

    pub fn uuid(&self) -> Result<String> {
        let public = self.signing_key()?.verifying_key().to_bytes();
        Ok(substratum_protocol::uuid(&public))
    }

    pub fn signing_key(&self) -> Result<SigningKey> {
        let invalid = || Error::Invalid("profile key is not 32 hex bytes".to_owned());
        if self.key.len() != 64 {
            return Err(invalid());
        }
        let mut seed = [0; 32];
        for (byte, pair) in seed.iter_mut().zip(self.key.as_bytes().as_chunks::<2>().0) {
            let text = std::str::from_utf8(pair.as_slice()).map_err(|_| invalid())?;
            *byte = u8::from_str_radix(text, 16).map_err(|_| invalid())?;
        }
        Ok(SigningKey::from_bytes(&seed))
    }

    pub fn preset(&self) -> usize {
        PRESETS
            .iter()
            .position(|preset| preset.name == self.skin)
            .unwrap_or(0)
    }

    pub fn choose_preset(&mut self, index: usize) {
        if let Some(preset) = PRESETS.get(index) {
            self.skin = preset.name.to_owned();
        }
    }

    pub fn skin(&self) -> Result<Skin> {
        Skin::preset(&PRESETS[self.preset()])
    }
}

pub fn sanitize_nick(text: &str) -> String {
    text.chars()
        .filter(|char| char.is_ascii_alphanumeric() || *char == '_')
        .take(NICK_MAX)
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn nicks_are_cleaned_to_minecraft_rules() {
        assert_eq!(
            sanitize_nick("ник Steve-_2 and more text"),
            "Steve_2andmorete"
        );
    }

    #[test]
    fn profiles_round_trip_with_a_stable_uuid() {
        let home = std::env::temp_dir().join(format!("substratum-profile-{}", std::process::id()));
        let created = Profile::load(&home).unwrap();
        let uuid = created.uuid().unwrap();
        assert_eq!(uuid.len(), 32);
        assert_eq!(&uuid[12..13], "4");
        let loaded = Profile::load(&home).unwrap();
        assert_eq!(loaded.uuid().unwrap(), uuid);
        assert_eq!(loaded.nick, DEFAULT_NICK);
        fs::remove_dir_all(&home).unwrap();
    }

    #[test]
    fn broken_keys_are_rejected() {
        let profile = Profile {
            nick: "Abc".into(),
            skin: "tape".into(),
            key: "zz".repeat(32),
        };
        assert!(profile.uuid().is_err());
    }
}
