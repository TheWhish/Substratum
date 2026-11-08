use std::fs;
use std::io;
use std::path::{Path, PathBuf};
use std::process::Command;

use ureq::Agent;

use crate::fetch::{self, Download, Hash, Progress, Result};
use crate::install::Release;

pub const VERSION: &str = env!("CARGO_PKG_VERSION");

pub fn outdated(release: &Release) -> bool {
    !cfg!(debug_assertions) && release.version != VERSION
}

pub fn download(agent: &Agent, release: &Release, progress: &Progress) -> Result<PathBuf> {
    let current = std::env::current_exe().map_err(fetch::io_error(Path::new("launcher")))?;
    let fresh = current.with_extension("new");
    let download = Download {
        url: release.url.clone(),
        path: fresh.clone(),
        size: Some(release.size),
        hash: Hash::sha256(&release.sha256)?,
    };
    fetch::all(agent, vec![download], progress)?;
    Ok(fresh)
}

pub fn apply(fresh: &Path) -> io::Result<()> {
    let current = std::env::current_exe()?;
    swap(&current, fresh)?;
    Command::new(&current)
        .args(std::env::args_os().skip(1))
        .spawn()?;
    Ok(())
}

fn swap(current: &Path, fresh: &Path) -> io::Result<()> {
    let old = current.with_extension("old");
    remove(&old)?;
    fs::rename(current, &old)?;
    if let Err(error) = fs::rename(fresh, current) {
        fs::rename(&old, current)?;
        return Err(error);
    }
    Ok(())
}

pub fn tidy() {
    let old = std::env::current_exe().map(|current| current.with_extension("old"));
    if let Err(error) = old.and_then(|old| remove(&old)) {
        eprintln!("failed to remove the previous launcher: {error}");
    }
}

fn remove(path: &Path) -> io::Result<()> {
    match fs::remove_file(path) {
        Err(error) if error.kind() != io::ErrorKind::NotFound => Err(error),
        _ => Ok(()),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_new_launcher_takes_the_place_of_the_old_one() {
        let dir = std::env::temp_dir().join("substratum-update-test");
        let _ = fs::remove_dir_all(&dir);
        fs::create_dir_all(&dir).unwrap();
        let current = dir.join("substratum-launcher.exe");
        let fresh = current.with_extension("new");
        for (old, new) in [("first", "second"), ("second", "third")] {
            fs::write(&current, old).unwrap();
            fs::write(&fresh, new).unwrap();
            swap(&current, &fresh).unwrap();
            assert_eq!(fs::read_to_string(&current).unwrap(), new);
            assert_eq!(
                fs::read_to_string(current.with_extension("old")).unwrap(),
                old
            );
            assert!(!fresh.exists());
        }
        assert!(swap(&current, &fresh).is_err());
        assert_eq!(fs::read_to_string(&current).unwrap(), "third");
        fs::remove_dir_all(&dir).unwrap();
    }
}
