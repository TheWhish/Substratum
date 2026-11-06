use std::fmt::{self, Write as _};
use std::fs::{self, File, OpenOptions};
use std::io::{self, Read, Write};
use std::path::{Component, Path, PathBuf};
use std::sync::Mutex;
use std::sync::atomic::{AtomicBool, AtomicU64, AtomicUsize, Ordering};
use std::thread;
use std::time::Duration;

use sha1::{Digest, Sha1};
use sha2::Sha256;
use ureq::Agent;

const WORKERS: usize = 12;
const ATTEMPTS: u32 = 4;
const CHUNK: usize = 64 * 1024;
const METADATA_LIMIT: u64 = 16 * 1024 * 1024;

#[derive(Debug)]
pub enum Error {
    Network { url: String, source: ureq::Error },
    Io { path: PathBuf, source: io::Error },
    Corrupt { url: String },
    Invalid(String),
    Cancelled,
}

impl fmt::Display for Error {
    fn fmt(&self, f: &mut fmt::Formatter) -> fmt::Result {
        match self {
            Error::Network { url, source } => write!(f, "request to {url} failed: {source}"),
            Error::Io { path, source } => write!(f, "{}: {source}", path.display()),
            Error::Corrupt { url } => write!(f, "{url} does not match its expected hash"),
            Error::Invalid(reason) => write!(f, "invalid build metadata: {reason}"),
            Error::Cancelled => f.write_str("cancelled"),
        }
    }
}

impl std::error::Error for Error {
    fn source(&self) -> Option<&(dyn std::error::Error + 'static)> {
        match self {
            Error::Network { source, .. } => Some(source),
            Error::Io { source, .. } => Some(source),
            _ => None,
        }
    }
}

pub type Result<T> = std::result::Result<T, Error>;

pub fn io_error(path: &Path) -> impl FnOnce(io::Error) -> Error {
    let path = path.to_path_buf();
    move |source| Error::Io { path, source }
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Hash {
    Sha1(String),
    Sha256(String),
}

impl Hash {
    pub fn sha1(hex: &str) -> Result<Hash> {
        checked(hex, 40).map(Hash::Sha1)
    }

    pub fn sha256(hex: &str) -> Result<Hash> {
        checked(hex, 64).map(Hash::Sha256)
    }

    fn hasher(&self) -> Hasher {
        match self {
            Hash::Sha1(_) => Hasher::Sha1(Sha1::new()),
            Hash::Sha256(_) => Hasher::Sha256(Sha256::new()),
        }
    }

    fn expected(&self) -> &str {
        match self {
            Hash::Sha1(hex) | Hash::Sha256(hex) => hex,
        }
    }
}

fn checked(hex: &str, length: usize) -> Result<String> {
    if hex.len() == length && hex.bytes().all(|byte| byte.is_ascii_hexdigit()) {
        return Ok(hex.to_ascii_lowercase());
    }
    Err(Error::Invalid(format!("malformed hash {hex:?}")))
}

enum Hasher {
    Sha1(Sha1),
    Sha256(Sha256),
}

impl Hasher {
    fn update(&mut self, bytes: &[u8]) {
        match self {
            Hasher::Sha1(hasher) => hasher.update(bytes),
            Hasher::Sha256(hasher) => hasher.update(bytes),
        }
    }

    fn hex(self) -> String {
        let digest: Vec<u8> = match self {
            Hasher::Sha1(hasher) => hasher.finalize().to_vec(),
            Hasher::Sha256(hasher) => hasher.finalize().to_vec(),
        };
        digest
            .iter()
            .fold(String::with_capacity(64), |mut hex, byte| {
                let _ = write!(hex, "{byte:02x}");
                hex
            })
    }
}

pub fn inside(base: &Path, relative: &str) -> Result<PathBuf> {
    let path = Path::new(relative);
    let plain = path
        .components()
        .all(|part| matches!(part, Component::Normal(_)));
    if relative.is_empty() || !plain {
        return Err(Error::Invalid(format!("unsafe path {relative:?}")));
    }
    Ok(base.join(path))
}

pub struct Download {
    pub url: String,
    pub path: PathBuf,
    pub size: Option<u64>,
    pub hash: Hash,
}

#[derive(Default)]
pub struct Progress {
    pub done: AtomicU64,
    pub total: AtomicU64,
    pub received: AtomicU64,
    pub cancel: AtomicBool,
}

impl Progress {
    fn check(&self) -> Result<()> {
        match self.cancel.load(Ordering::Relaxed) {
            true => Err(Error::Cancelled),
            false => Ok(()),
        }
    }
}

pub fn agent() -> Agent {
    Agent::config_builder()
        .user_agent(concat!("substratum-launcher/", env!("CARGO_PKG_VERSION")))
        .timeout_connect(Some(Duration::from_secs(15)))
        .timeout_recv_response(Some(Duration::from_secs(30)))
        .timeout_per_read(Some(Duration::from_secs(30)))
        .build()
        .into()
}

pub fn bytes(agent: &Agent, url: &str, hash: Option<&Hash>) -> Result<Vec<u8>> {
    let network = |source| Error::Network {
        url: url.to_owned(),
        source,
    };
    let body = agent
        .get(url)
        .call()
        .map_err(network)?
        .body_mut()
        .with_config()
        .limit(METADATA_LIMIT)
        .read_to_vec()
        .map_err(network)?;
    if let Some(hash) = hash {
        let mut hasher = hash.hasher();
        hasher.update(&body);
        if hasher.hex() != hash.expected() {
            return Err(Error::Corrupt {
                url: url.to_owned(),
            });
        }
    }
    Ok(body)
}

pub fn all(agent: &Agent, mut downloads: Vec<Download>, progress: &Progress) -> Result<()> {
    downloads.sort_by(|a, b| a.path.cmp(&b.path));
    downloads.dedup_by(|a, b| a.path == b.path);
    downloads.sort_by_key(|download| std::cmp::Reverse(download.size.unwrap_or(0)));
    let total = downloads.iter().filter_map(|download| download.size).sum();
    progress.total.store(total, Ordering::Relaxed);

    let next = AtomicUsize::new(0);
    let failure = Mutex::new(None);
    thread::scope(|scope| {
        for _ in 0..WORKERS {
            scope.spawn(|| {
                while progress.check().is_ok() {
                    let Some(download) = downloads.get(next.fetch_add(1, Ordering::Relaxed)) else {
                        return;
                    };
                    if let Err(error) = fetch(agent, download, progress) {
                        let mut failure =
                            failure.lock().unwrap_or_else(|poison| poison.into_inner());
                        if failure.is_none() && !matches!(error, Error::Cancelled) {
                            *failure = Some(error);
                            progress.cancel.store(true, Ordering::Relaxed);
                        }
                        return;
                    }
                }
            });
        }
    });

    let failure = failure
        .into_inner()
        .unwrap_or_else(|poison| poison.into_inner());
    failure.map_or_else(|| progress.check(), Err)
}

fn fetch(agent: &Agent, download: &Download, progress: &Progress) -> Result<()> {
    if verified(&download.path, download, progress)? {
        return Ok(());
    }
    let mut attempt = 1;
    loop {
        let mut counted = 0;
        let result = transfer(agent, download, progress, &mut counted);
        if result.is_ok() {
            return result;
        }
        progress.done.fetch_sub(counted, Ordering::Relaxed);
        match result {
            Err(Error::Network { .. } | Error::Corrupt { .. }) if attempt < ATTEMPTS => {
                pause(Duration::from_secs(1 << (attempt - 1)), progress)?;
                attempt += 1;
            }
            _ => return result,
        }
    }
}

fn pause(duration: Duration, progress: &Progress) -> Result<()> {
    let step = Duration::from_millis(100);
    let mut waited = Duration::ZERO;
    while waited < duration {
        progress.check()?;
        thread::sleep(step);
        waited += step;
    }
    progress.check()
}

fn verified(path: &Path, download: &Download, progress: &Progress) -> Result<bool> {
    let Ok(file) = File::open(path) else {
        return Ok(false);
    };
    let length = file.metadata().map_err(io_error(path))?.len();
    if download.size.is_some_and(|size| size != length) {
        return Ok(false);
    }
    let mut hasher = download.hash.hasher();
    let mut counted = 0;
    let read = absorb(
        file,
        &mut hasher,
        path,
        download.size.is_some(),
        progress,
        &mut counted,
    );
    let matches = read.is_ok() && hasher.hex() == download.hash.expected();
    if !matches {
        progress.done.fetch_sub(counted, Ordering::Relaxed);
    }
    read.map(|()| matches)
}

fn absorb(
    mut file: File,
    hasher: &mut Hasher,
    path: &Path,
    tracked: bool,
    progress: &Progress,
    counted: &mut u64,
) -> Result<()> {
    let mut buffer = vec![0; CHUNK];
    loop {
        progress.check()?;
        let read = file.read(&mut buffer).map_err(io_error(path))?;
        if read == 0 {
            return Ok(());
        }
        hasher.update(&buffer[..read]);
        if tracked {
            *counted += read as u64;
            progress.done.fetch_add(read as u64, Ordering::Relaxed);
        }
    }
}

fn transfer(
    agent: &Agent,
    download: &Download,
    progress: &Progress,
    counted: &mut u64,
) -> Result<()> {
    let path = &download.path;
    let part = partial(path);
    let parent = path
        .parent()
        .ok_or_else(|| Error::Invalid(format!("no parent for {}", path.display())))?;
    fs::create_dir_all(parent).map_err(io_error(parent))?;

    let tracked = download.size.is_some();
    let mut hasher = download.hash.hasher();
    let offset = match (download.size, fs::metadata(&part)) {
        (Some(size), Ok(meta)) if meta.len() < size => meta.len(),
        _ => 0,
    };
    if offset > 0 {
        let file = File::open(&part).map_err(io_error(&part))?;
        absorb(file, &mut hasher, &part, tracked, progress, counted)?;
    }

    let network = |source| Error::Network {
        url: download.url.clone(),
        source,
    };
    let mut request = agent.get(&download.url);
    if offset > 0 {
        request = request.header("Range", format!("bytes={offset}-"));
    }
    let mut response = request.call().map_err(network)?;
    let resumed = offset > 0 && response.status() == 206;
    if offset > 0 && !resumed {
        progress.done.fetch_sub(*counted, Ordering::Relaxed);
        *counted = 0;
        hasher = download.hash.hasher();
    }

    let mut file = OpenOptions::new()
        .create(true)
        .write(true)
        .append(resumed)
        .truncate(!resumed)
        .open(&part)
        .map_err(io_error(&part))?;
    let mut body = response.body_mut().as_reader();
    let mut buffer = vec![0; CHUNK];
    loop {
        progress.check()?;
        let read =
            body.read(&mut buffer)
                .map_err(|source| match source.downcast::<ureq::Error>() {
                    Ok(source) => network(source),
                    Err(source) => network(ureq::Error::Io(source)),
                })?;
        if read == 0 {
            break;
        }
        file.write_all(&buffer[..read]).map_err(io_error(&part))?;
        hasher.update(&buffer[..read]);
        progress.received.fetch_add(read as u64, Ordering::Relaxed);
        if tracked {
            *counted += read as u64;
            progress.done.fetch_add(read as u64, Ordering::Relaxed);
        }
    }
    file.sync_all().map_err(io_error(&part))?;
    drop(file);

    if hasher.hex() != download.hash.expected() {
        fs::remove_file(&part).map_err(io_error(&part))?;
        return Err(Error::Corrupt {
            url: download.url.clone(),
        });
    }
    fs::rename(&part, path).map_err(io_error(path))
}

fn partial(path: &Path) -> PathBuf {
    let mut name = path.as_os_str().to_owned();
    name.push(".part");
    PathBuf::from(name)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn inside_rejects_escapes() {
        let base = Path::new("base");
        assert_eq!(inside(base, "a/b.jar").unwrap(), base.join("a/b.jar"));
        for unsafe_path in ["", "../x", "a/../../x", "/etc/passwd", "C:\\x"] {
            assert!(inside(base, unsafe_path).is_err(), "{unsafe_path}");
        }
    }

    #[test]
    fn hashes_are_validated_and_computed() {
        assert!(Hash::sha1("abc").is_err());
        assert!(Hash::sha256(&"g".repeat(64)).is_err());
        let hash = Hash::sha1("A9993E364706816ABA3E25717850C26C9CD0D89D").unwrap();
        let mut hasher = hash.hasher();
        hasher.update(b"abc");
        assert_eq!(hasher.hex(), hash.expected());
    }
}
