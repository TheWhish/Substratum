use std::net::SocketAddr;

use ed25519_dalek::{Signature, Verifier, VerifyingKey};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};

pub const CONTROL_PATH: &str = "/team";
pub const RELAY_PATH: &str = "/relay";
pub const MAX_PLAYERS: usize = 4;
pub const CODE_LENGTH: usize = 8;
pub const CODE_ALPHABET: &[u8] = b"ABCDEFGHJKMNPQRSTUVWXYZ23456789";
pub const NICK_MIN: usize = 3;
pub const NICK_MAX: usize = 16;
pub const RELAY_CHUNK: usize = 16 * 1024;
pub const BEACON_PORT: u16 = 7878;
pub const BEACON_LENGTH: usize = 16;
const SKIN_MAX: usize = 16;
const BUILD_MAX: usize = 32;
const HELLO_CONTEXT: &str = "substratum-hello:";

#[derive(Serialize, Deserialize, Clone, Copy, PartialEq, Eq, Debug, Default)]
#[serde(rename_all = "kebab-case")]
pub enum Presence {
    #[default]
    Away,
    Menu,
    Ready,
    Playing,
}

#[derive(Serialize, Deserialize, Clone, PartialEq, Eq, Debug)]
pub struct Profile {
    pub nick: String,
    pub skin: String,
    pub build: String,
}

impl Profile {
    pub fn valid(&self) -> bool {
        valid_nick(&self.nick) && valid_skin(&self.skin) && valid_build(&self.build)
    }
}

#[derive(Serialize, Deserialize, Clone, PartialEq, Eq, Debug)]
pub struct Member {
    pub uuid: String,
    pub nick: String,
    pub skin: String,
    pub build: String,
    pub presence: Presence,
    pub online: bool,
}

#[derive(Serialize, Deserialize, Clone, PartialEq, Eq, Debug)]
pub struct Session {
    pub participants: Vec<String>,
    pub open: bool,
}

#[derive(Serialize, Deserialize, Clone, PartialEq, Eq, Debug)]
pub struct Team {
    pub code: String,
    pub host: String,
    pub members: Vec<Member>,
    pub session: Option<Session>,
}

impl Team {
    pub fn member(&self, uuid: &str) -> Option<&Member> {
        self.members.iter().find(|member| member.uuid == uuid)
    }

    pub fn takes_part(&self, uuid: &str) -> bool {
        self.session
            .as_ref()
            .is_some_and(|session| session.participants.iter().any(|id| id == uuid))
    }
}

#[derive(Serialize, Deserialize, Clone, PartialEq, Eq, Debug)]
#[serde(tag = "type", rename_all = "kebab-case")]
pub enum ToServer {
    Hello {
        key: String,
        signature: String,
        #[serde(flatten)]
        profile: Profile,
    },
    Profile(Profile),
    Join {
        code: String,
    },
    Leave,
    Kick {
        uuid: String,
    },
    Presence {
        presence: Presence,
    },
    Start,
    Opened,
    Ended,
    Punch,
}

#[derive(Serialize, Deserialize, Clone, PartialEq, Eq, Debug)]
#[serde(tag = "type", rename_all = "kebab-case")]
pub enum ToClient {
    Challenge {
        nonce: String,
    },
    Welcome {
        uuid: String,
        token: String,
        beacon: String,
    },
    Team {
        team: Team,
    },
    Refused {
        reason: Refusal,
    },
    Incoming {
        stream: u64,
    },
    Punch {
        peer: String,
        address: SocketAddr,
    },
}

#[derive(Serialize, Deserialize, Clone, Copy, PartialEq, Eq, Debug)]
#[serde(rename_all = "kebab-case")]
pub enum Refusal {
    Unknown,
    Full,
    Locked,
    NickTaken,
    NotHost,
    NotReady,
    Invalid,
}

#[derive(Serialize, Deserialize, Clone, PartialEq, Eq, Debug)]
#[serde(tag = "role", rename_all = "kebab-case")]
pub enum Relay {
    Dial { token: String },
    Accept { token: String, stream: u64 },
}

pub fn uuid(public: &[u8; 32]) -> String {
    let digest = Sha256::digest(public);
    let mut bytes = [0; 16];
    bytes.copy_from_slice(&digest[..16]);
    bytes[6] = bytes[6] & 0x0f | 0x40;
    bytes[8] = bytes[8] & 0x3f | 0x80;
    hex(&bytes)
}

pub fn hello_message(nonce: &str) -> Vec<u8> {
    format!("{HELLO_CONTEXT}{nonce}").into_bytes()
}

pub fn verify_hello(key: &str, signature: &str, nonce: &str) -> Option<String> {
    let key: [u8; 32] = unhex(key)?.try_into().ok()?;
    let signature: [u8; 64] = unhex(signature)?.try_into().ok()?;
    let verifying = VerifyingKey::from_bytes(&key).ok()?;
    verifying
        .verify(&hello_message(nonce), &Signature::from_bytes(&signature))
        .ok()?;
    Some(uuid(&key))
}

pub fn valid_nick(nick: &str) -> bool {
    (NICK_MIN..=NICK_MAX).contains(&nick.len())
        && nick
            .chars()
            .all(|char| char.is_ascii_alphanumeric() || char == '_')
}

fn valid_skin(skin: &str) -> bool {
    (1..=SKIN_MAX).contains(&skin.len()) && skin.chars().all(|char| char.is_ascii_lowercase())
}

fn valid_build(build: &str) -> bool {
    build.len() <= BUILD_MAX
        && build
            .chars()
            .all(|char| char.is_ascii_alphanumeric() || ".-+_".contains(char))
}

pub fn valid_code(code: &str) -> bool {
    code.len() == CODE_LENGTH && code.bytes().all(|byte| CODE_ALPHABET.contains(&byte))
}

pub fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|byte| format!("{byte:02x}")).collect()
}

pub fn unhex(text: &str) -> Option<Vec<u8>> {
    if !text.len().is_multiple_of(2) {
        return None;
    }
    (0..text.len())
        .step_by(2)
        .map(|at| u8::from_str_radix(text.get(at..at + 2)?, 16).ok())
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;
    use ed25519_dalek::{Signer, SigningKey};

    #[test]
    fn hello_proves_the_key() {
        let key = SigningKey::from_bytes(&[7; 32]);
        let public = key.verifying_key().to_bytes();
        let signature = hex(&key.sign(&hello_message("abc")).to_bytes());
        assert_eq!(
            verify_hello(&hex(&public), &signature, "abc"),
            Some(uuid(&public))
        );
        assert_eq!(verify_hello(&hex(&public), &signature, "abd"), None);
        assert_eq!(verify_hello("zz", &signature, "abc"), None);
    }

    #[test]
    fn messages_keep_their_wire_shape() {
        let text = serde_json::to_string(&ToServer::Presence {
            presence: Presence::Ready,
        });
        assert_eq!(text.unwrap(), r#"{"type":"presence","presence":"ready"}"#);
        let profile = Profile {
            nick: "Alpha".into(),
            skin: "red".into(),
            build: "1".into(),
        };
        let hello = ToServer::Hello {
            key: "k".into(),
            signature: "s".into(),
            profile: profile.clone(),
        };
        let text = serde_json::to_string(&hello).unwrap();
        assert_eq!(
            text,
            r#"{"type":"hello","key":"k","signature":"s","nick":"Alpha","skin":"red","build":"1"}"#
        );
        assert_eq!(serde_json::from_str::<ToServer>(&text).unwrap(), hello);
        let text = serde_json::to_string(&ToServer::Profile(profile.clone())).unwrap();
        assert_eq!(
            text,
            r#"{"type":"profile","nick":"Alpha","skin":"red","build":"1"}"#
        );
        assert_eq!(
            serde_json::from_str::<ToServer>(&text).unwrap(),
            ToServer::Profile(profile)
        );
    }

    #[test]
    fn inputs_are_validated() {
        assert!(valid_nick("Player_1"));
        assert!(!valid_nick("ab") && !valid_nick("with space"));
        assert!(valid_skin("yellow") && !valid_skin("../x"));
        assert!(valid_build("0.1.0-alpha") && !valid_build("a b"));
        assert!(valid_code("ABCD2345") && !valid_code("ABCD-234") && !valid_code("ABCD0345"));
        assert_eq!(unhex("0aff"), Some(vec![10, 255]));
        assert_eq!(unhex("0g"), None);
    }
}
