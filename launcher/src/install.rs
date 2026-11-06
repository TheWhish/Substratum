use std::collections::{HashMap, HashSet};
use std::ffi::OsStr;
use std::fs;
use std::path::{Path, PathBuf};

use serde::Deserialize;
use serde::de::DeserializeOwned;
use ureq::Agent;

use crate::fetch::{self, Download, Error, Hash, Progress, Result, inside, io_error};

const MANIFEST_URL: &str =
    "https://github.com/TheWhish/Substratum/releases/latest/download/manifest.json";
const VERSIONS_URL: &str = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
const JAVA_URL: &str = "https://launchermeta.mojang.com/v1/products/java-runtime/2ec0cc96c44e5a76b9c8b7c39df7210883d12871/all.json";
const ASSETS_URL: &str = "https://resources.download.minecraft.net";
const FABRIC_URL: &str = "https://meta.fabricmc.net/v2/versions/loader";
const OS: &str = "windows";
const JAVA_PLATFORM: &str = "windows-x64";
const BUILD_FILE: &str = "build.json";

#[derive(Deserialize)]
struct Manifest {
    build: String,
    minecraft: String,
    fabric: String,
    mods: Vec<Mod>,
}

#[derive(Deserialize)]
struct Mod {
    file: String,
    url: String,
    size: u64,
    sha256: String,
}

#[derive(Deserialize)]
struct FileRef {
    url: String,
    sha1: String,
    size: u64,
}

impl FileRef {
    fn download(&self, path: PathBuf) -> Result<Download> {
        Ok(Download {
            url: self.url.clone(),
            path,
            size: Some(self.size),
            hash: Hash::sha1(&self.sha1)?,
        })
    }
}

#[derive(Deserialize)]
struct VersionList {
    versions: Vec<VersionRef>,
}

#[derive(Deserialize)]
struct VersionRef {
    id: String,
    url: String,
    sha1: String,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct Version {
    asset_index: AssetIndexRef,
    downloads: ClientDownloads,
    libraries: Vec<Library>,
    java_version: JavaVersion,
    logging: Option<Logging>,
}

#[derive(Deserialize)]
struct AssetIndexRef {
    id: String,
    #[serde(flatten)]
    file: FileRef,
}

#[derive(Deserialize)]
struct ClientDownloads {
    client: FileRef,
}

#[derive(Deserialize)]
struct Library {
    downloads: LibraryDownloads,
    #[serde(default)]
    rules: Vec<Rule>,
}

#[derive(Deserialize)]
struct LibraryDownloads {
    artifact: Option<Artifact>,
}

#[derive(Deserialize)]
struct Artifact {
    path: String,
    #[serde(flatten)]
    file: FileRef,
}

#[derive(Deserialize)]
struct Rule {
    action: String,
    os: Option<Os>,
}

#[derive(Deserialize)]
struct Os {
    name: Option<String>,
}

#[derive(Deserialize)]
struct JavaVersion {
    component: String,
}

#[derive(Deserialize)]
struct Logging {
    client: LoggingClient,
}

#[derive(Deserialize)]
struct LoggingClient {
    file: LogFile,
}

#[derive(Deserialize)]
struct LogFile {
    id: String,
    #[serde(flatten)]
    file: FileRef,
}

#[derive(Deserialize)]
struct AssetIndex {
    objects: HashMap<String, Asset>,
}

#[derive(Deserialize)]
struct Asset {
    hash: String,
    size: u64,
}

#[derive(Deserialize)]
struct JavaRelease {
    manifest: FileRef,
}

#[derive(Deserialize)]
struct JavaManifest {
    files: HashMap<String, JavaFile>,
}

#[derive(Deserialize)]
struct JavaFile {
    #[serde(rename = "type")]
    kind: String,
    downloads: Option<JavaDownloads>,
}

#[derive(Deserialize)]
struct JavaDownloads {
    raw: FileRef,
}

#[derive(Deserialize)]
struct FabricProfile {
    id: String,
    libraries: Vec<FabricLibrary>,
}

#[derive(Deserialize)]
struct FabricLibrary {
    name: String,
    url: String,
    sha1: Option<String>,
    size: Option<u64>,
}

pub fn home() -> Option<PathBuf> {
    std::env::var_os("APPDATA").map(|appdata| PathBuf::from(appdata).join("Substratum"))
}

pub fn installed(home: &Path) -> Option<String> {
    let bytes = fs::read(home.join(BUILD_FILE)).ok()?;
    serde_json::from_slice::<Manifest>(&bytes)
        .ok()
        .map(|manifest| manifest.build)
}

pub fn run(home: &Path, progress: &Progress) -> Result<String> {
    let agent = fetch::agent();
    let manifest_url =
        std::env::var("SUBSTRATUM_MANIFEST").unwrap_or_else(|_| MANIFEST_URL.to_owned());
    let manifest_bytes = fetch::bytes(&agent, &manifest_url, None)?;
    let manifest: Manifest = parse(&manifest_bytes, &manifest_url)?;

    let mut downloads = minecraft(&agent, home, &manifest.minecraft)?;
    downloads.extend(fabric(&agent, home, &manifest)?);
    downloads.extend(mods(home, &manifest.mods)?);
    fetch::all(&agent, downloads, progress)?;

    prune_mods(home, &manifest.mods)?;
    save(&home.join(BUILD_FILE), &manifest_bytes)?;
    Ok(manifest.build)
}

fn minecraft(agent: &Agent, home: &Path, id: &str) -> Result<Vec<Download>> {
    let list: VersionList = parse(&fetch::bytes(agent, VERSIONS_URL, None)?, VERSIONS_URL)?;
    let reference = list
        .versions
        .into_iter()
        .find(|version| version.id == id)
        .ok_or_else(|| Error::Invalid(format!("Minecraft {id} is not published")))?;
    let version_dir = inside(&home.join("versions"), id)?;
    let bytes = fetch::bytes(agent, &reference.url, Some(&Hash::sha1(&reference.sha1)?))?;
    save(&version_dir.join(format!("{id}.json")), &bytes)?;
    let version: Version = parse(&bytes, &reference.url)?;

    let libraries = home.join("libraries");
    let mut downloads = vec![
        version
            .downloads
            .client
            .download(version_dir.join(format!("{id}.jar")))?,
    ];
    for library in version
        .libraries
        .iter()
        .filter(|library| allowed(&library.rules))
    {
        if let Some(artifact) = &library.downloads.artifact {
            downloads.push(
                artifact
                    .file
                    .download(inside(&libraries, &artifact.path)?)?,
            );
        }
    }
    if let Some(logging) = &version.logging {
        let path = inside(
            &home.join("assets").join("log_configs"),
            &logging.client.file.id,
        )?;
        downloads.push(logging.client.file.file.download(path)?);
    }
    downloads.extend(assets(agent, home, &version.asset_index)?);
    downloads.extend(java(agent, home, &version.java_version.component)?);
    Ok(downloads)
}

fn allowed(rules: &[Rule]) -> bool {
    rules.is_empty()
        || rules.iter().fold(false, |allowed, rule| {
            let name = rule.os.as_ref().and_then(|os| os.name.as_deref());
            match name.is_none_or(|name| name == OS) {
                true => rule.action == "allow",
                false => allowed,
            }
        })
}

fn assets(agent: &Agent, home: &Path, index: &AssetIndexRef) -> Result<Vec<Download>> {
    let assets = home.join("assets");
    let bytes = fetch::bytes(agent, &index.file.url, Some(&Hash::sha1(&index.file.sha1)?))?;
    save(
        &inside(&assets.join("indexes"), &format!("{}.json", index.id))?,
        &bytes,
    )?;
    let index: AssetIndex = parse(&bytes, &index.file.url)?;

    let objects = assets.join("objects");
    let mut seen = HashSet::new();
    let mut downloads = Vec::new();
    for asset in index.objects.into_values() {
        let hash = Hash::sha1(&asset.hash)?;
        let hex = asset.hash.to_ascii_lowercase();
        if !seen.insert(hex.clone()) {
            continue;
        }
        let relative = format!("{}/{hex}", &hex[..2]);
        downloads.push(Download {
            url: format!("{ASSETS_URL}/{relative}"),
            path: objects.join(relative),
            size: Some(asset.size),
            hash,
        });
    }
    Ok(downloads)
}

fn java(agent: &Agent, home: &Path, component: &str) -> Result<Vec<Download>> {
    let mut platforms: HashMap<String, HashMap<String, Vec<JavaRelease>>> =
        parse(&fetch::bytes(agent, JAVA_URL, None)?, JAVA_URL)?;
    let release = platforms
        .remove(JAVA_PLATFORM)
        .and_then(|mut components| components.remove(component))
        .and_then(|releases| releases.into_iter().next())
        .ok_or_else(|| {
            Error::Invalid(format!(
                "Java runtime {component} is not published for {JAVA_PLATFORM}"
            ))
        })?;
    let url = &release.manifest.url;
    let manifest: JavaManifest = parse(
        &fetch::bytes(agent, url, Some(&Hash::sha1(&release.manifest.sha1)?))?,
        url,
    )?;

    let runtime = inside(&home.join("runtime"), component)?;
    manifest
        .files
        .iter()
        .filter(|(_, file)| file.kind == "file")
        .map(|(name, file)| {
            let raw = &file
                .downloads
                .as_ref()
                .ok_or_else(|| Error::Invalid(format!("{name} has no download")))?
                .raw;
            raw.download(inside(&runtime, name)?)
        })
        .collect()
}

fn fabric(agent: &Agent, home: &Path, manifest: &Manifest) -> Result<Vec<Download>> {
    let url = format!(
        "{FABRIC_URL}/{}/{}/profile/json",
        manifest.minecraft, manifest.fabric
    );
    let bytes = fetch::bytes(agent, &url, None)?;
    let profile: FabricProfile = parse(&bytes, &url)?;
    save(
        &inside(&home.join("versions"), &format!("{0}/{0}.json", profile.id))?,
        &bytes,
    )?;

    let libraries = home.join("libraries");
    profile
        .libraries
        .iter()
        .map(|library| {
            let path = maven_path(&library.name)?;
            let url = format!("{}/{path}", library.url.trim_end_matches('/'));
            let sha1 = match &library.sha1 {
                Some(sha1) => sha1.clone(),
                None => {
                    String::from_utf8_lossy(&fetch::bytes(agent, &format!("{url}.sha1"), None)?)
                        .trim()
                        .to_owned()
                }
            };
            Ok(Download {
                path: inside(&libraries, &path)?,
                url,
                size: library.size,
                hash: Hash::sha1(&sha1)?,
            })
        })
        .collect()
}

fn maven_path(name: &str) -> Result<String> {
    let parts: Vec<&str> = name.split(':').collect();
    let (group, artifact, version, classifier) = match parts[..] {
        [group, artifact, version] => (group, artifact, version, String::new()),
        [group, artifact, version, classifier] => {
            (group, artifact, version, format!("-{classifier}"))
        }
        _ => return Err(Error::Invalid(format!("malformed library name {name:?}"))),
    };
    Ok(format!(
        "{}/{artifact}/{version}/{artifact}-{version}{classifier}.jar",
        group.replace('.', "/")
    ))
}

fn mods(home: &Path, mods: &[Mod]) -> Result<Vec<Download>> {
    let dir = home.join("instance").join("mods");
    mods.iter()
        .map(|entry| {
            Ok(Download {
                url: entry.url.clone(),
                path: dir.join(mod_file(&entry.file)?),
                size: Some(entry.size),
                hash: Hash::sha256(&entry.sha256)?,
            })
        })
        .collect()
}

fn mod_file(file: &str) -> Result<&str> {
    let plain = Path::new(file).file_name() == Some(OsStr::new(file));
    match plain && file.ends_with(".jar") {
        true => Ok(file),
        false => Err(Error::Invalid(format!("unsafe mod file name {file:?}"))),
    }
}

fn prune_mods(home: &Path, mods: &[Mod]) -> Result<()> {
    let dir = home.join("instance").join("mods");
    let keep: HashSet<&OsStr> = mods.iter().map(|entry| OsStr::new(&entry.file)).collect();
    fs::create_dir_all(&dir).map_err(io_error(&dir))?;
    for entry in fs::read_dir(&dir).map_err(io_error(&dir))? {
        let path = entry.map_err(io_error(&dir))?.path();
        let foreign = path.extension() == Some(OsStr::new("jar"))
            && !keep.contains(path.file_name().unwrap_or_default());
        if foreign {
            fs::remove_file(&path).map_err(io_error(&path))?;
        }
    }
    Ok(())
}

fn parse<T: DeserializeOwned>(bytes: &[u8], url: &str) -> Result<T> {
    serde_json::from_slice(bytes).map_err(|error| Error::Invalid(format!("{url}: {error}")))
}

fn save(path: &Path, bytes: &[u8]) -> Result<()> {
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent).map_err(io_error(parent))?;
    }
    fs::write(path, bytes).map_err(io_error(path))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn rule(action: &str, os: Option<&str>) -> Rule {
        Rule {
            action: action.to_owned(),
            os: Some(Os {
                name: os.map(str::to_owned),
            }),
        }
    }

    #[test]
    fn rules_follow_the_last_matching_entry() {
        assert!(allowed(&[]));
        assert!(allowed(&[rule("allow", Some("windows"))]));
        assert!(!allowed(&[rule("allow", Some("osx"))]));
        assert!(!allowed(&[
            rule("allow", None),
            rule("disallow", Some("windows"))
        ]));
        assert!(allowed(&[
            rule("allow", None),
            rule("disallow", Some("linux"))
        ]));
    }

    #[test]
    fn maven_names_map_to_repository_paths() {
        assert_eq!(
            maven_path("net.fabricmc:intermediary:1.21.1").unwrap(),
            "net/fabricmc/intermediary/1.21.1/intermediary-1.21.1.jar"
        );
        assert_eq!(
            maven_path("a.b:c:1:natives").unwrap(),
            "a/b/c/1/c-1-natives.jar"
        );
        assert!(maven_path("broken").is_err());
    }

    #[test]
    #[ignore = "downloads the whole build, about 1 GB"]
    fn installs_reuses_and_repairs() {
        use std::sync::atomic::Ordering;

        let home = std::env::temp_dir().join("substratum-install-test");
        let install = || {
            let progress = Progress::default();
            let build = run(&home, &progress).unwrap();
            assert_eq!(installed(&home).as_deref(), Some(build.as_str()));
            progress.received.load(Ordering::Relaxed)
        };
        install();
        assert_eq!(install(), 0);

        let client = home.join("versions/1.21.1/1.21.1.jar");
        fs::write(&client, b"broken").unwrap();
        assert!(install() > 0);
        assert_eq!(install(), 0);

        let jar = fs::read(&client).unwrap();
        let half = jar.len() / 2;
        fs::remove_file(&client).unwrap();
        fs::write(home.join("versions/1.21.1/1.21.1.jar.part"), &jar[..half]).unwrap();
        assert_eq!(install(), (jar.len() - half) as u64);
    }

    #[test]
    fn mod_files_must_be_plain_jars() {
        assert!(mod_file("sodium.jar").is_ok());
        for name in ["../x.jar", "mods/x.jar", "x.zip", "C:\\x.jar", ""] {
            assert!(mod_file(name).is_err(), "{name}");
        }
    }
}
