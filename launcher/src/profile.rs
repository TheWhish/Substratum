use std::fs;
use std::io;
use std::path::{Path, PathBuf};

use ed25519_dalek::SigningKey;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};

use crate::fetch::{Error, Result, hex, io_error};
use crate::install;
use crate::skin::{PRESETS, Skin};

const FILE: &str = "profile.toml";
const BROKEN: &str = "profile.broken.toml";
const CUSTOM_SKIN: &str = "skins/custom.png";
const ACTIVE_SKIN: &str = "skins/active.png";
const CUSTOM: &str = "custom";
const DEFAULT_NICK: &str = "PLAYER";
pub const NICK_MAX: usize = 16;
const NICK_MIN: usize = 3;

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
        let known = PRESETS.iter().any(|preset| preset.name == profile.skin);
        let custom = profile.skin == CUSTOM && home.join(CUSTOM_SKIN).is_file();
        if !known && !custom {
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
        let digest = Sha256::digest(public);
        let mut bytes = [0; 16];
        bytes.copy_from_slice(&digest[..16]);
        bytes[6] = bytes[6] & 0x0f | 0x40;
        bytes[8] = bytes[8] & 0x3f | 0x80;
        Ok(hex(&bytes))
    }

    fn signing_key(&self) -> Result<SigningKey> {
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

    pub fn preset(&self) -> Option<usize> {
        PRESETS.iter().position(|preset| preset.name == self.skin)
    }

    pub fn choose_preset(&mut self, index: usize) {
        if let Some(preset) = PRESETS.get(index) {
            self.skin = preset.name.to_owned();
        }
    }

    pub fn adopt_custom(&mut self, home: &Path, skin: &Skin) -> Result<()> {
        install::save(&home.join(CUSTOM_SKIN), &skin.encode()?)?;
        self.skin = CUSTOM.to_owned();
        Ok(())
    }

    pub fn custom_skin(home: &Path) -> Result<Option<Skin>> {
        let path = home.join(CUSTOM_SKIN);
        match path.is_file() {
            true => Skin::read(&path).map(Some),
            false => Ok(None),
        }
    }

    pub fn choose_custom(&mut self, home: &Path) -> bool {
        let available = home.join(CUSTOM_SKIN).is_file();
        if available {
            self.skin = CUSTOM.to_owned();
        }
        available
    }

    pub fn skin(&self, home: &Path) -> Result<Skin> {
        match self.preset() {
            Some(index) => Skin::preset(&PRESETS[index]),
            None => Skin::read(&home.join(CUSTOM_SKIN)),
        }
    }

    pub fn active_skin(&self, home: &Path) -> Result<PathBuf> {
        let path = home.join(ACTIVE_SKIN);
        install::save(&path, &self.skin(home)?.encode()?)?;
        Ok(path)
    }
}

pub fn sanitize_nick(text: &str) -> String {
    text.chars()
        .filter(|char| char.is_ascii_alphanumeric() || *char == '_')
        .take(NICK_MAX)
        .collect()
}

pub fn valid_nick(nick: &str) -> bool {
    (NICK_MIN..=NICK_MAX).contains(&nick.len()) && sanitize_nick(nick) == nick
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn nicks_follow_minecraft_rules() {
        for nick in ["Abc", "player_16_chars_", "PLAYER"] {
            assert!(valid_nick(nick), "{nick}");
        }
        for nick in ["ab", "seventeen_chars__", "with space", "ник", "a-b", ""] {
            assert!(!valid_nick(nick), "{nick}");
        }
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
