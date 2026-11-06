use std::collections::HashMap;
use std::env;
use std::fs::{self, File};
use std::path::{Path, PathBuf};
use std::process::{Child, Command, Stdio};

use crate::fetch::{Error, Result, inside, io_error};
use crate::install::{self, Argument, FabricProfile, Version};
use crate::profile::Profile;

const MEMORY: &str = "-Xmx4G";
const JAVA: &str = "bin/javaw.exe";
const LAUNCHER_NAME: &str = "substratum-launcher";

pub fn instance(home: &Path) -> PathBuf {
    home.join("instance")
}

pub fn start(home: &Path, profile: &Profile) -> Result<Child> {
    let manifest = install::manifest(home)?;
    let version_dir = install::version_dir(home, &manifest.minecraft)?;
    let version: Version =
        install::load(&version_dir.join(format!("{}.json", manifest.minecraft)))?;
    let fabric: FabricProfile = install::load(&install::fabric_profile(home, &manifest)?)?;

    let instance = instance(home);
    let natives = version_dir.join("natives");
    let assets = home.join("assets");
    let logs = instance.join("logs");
    for dir in [&logs, &natives] {
        fs::create_dir_all(dir).map_err(io_error(dir))?;
    }

    let vars = HashMap::from([
        ("auth_player_name", profile.nick.clone()),
        ("auth_uuid", profile.uuid()?),
        ("auth_access_token", "0".to_owned()),
        ("auth_xuid", String::new()),
        ("clientid", String::new()),
        ("user_type", "legacy".to_owned()),
        ("version_name", fabric.id.clone()),
        ("version_type", version.kind.clone()),
        ("game_directory", text(&instance)?),
        ("assets_root", text(&assets)?),
        ("assets_index_name", version.asset_index.id.clone()),
        ("natives_directory", text(&natives)?),
        ("launcher_name", LAUNCHER_NAME.to_owned()),
        ("launcher_version", env!("CARGO_PKG_VERSION").to_owned()),
        (
            "classpath",
            classpath(home, &version, &fabric, &version_dir)?,
        ),
    ]);

    let skin = profile.active_skin(home)?;
    let mut args = vec![
        MEMORY.to_owned(),
        format!("-Dsubstratum.skin={}", text(&skin)?),
    ];
    if let Some(logging) = &version.logging {
        let config = inside(&assets.join("log_configs"), &logging.client.file.id)?;
        args.push(logging.client.argument.replace("${path}", &text(&config)?));
    }
    let jvm = version.arguments.jvm.iter().chain(&fabric.arguments.jvm);
    args.extend(jvm.flat_map(Argument::values).map(|arg| fill(arg, &vars)));
    args.push(fabric.main_class.clone());
    let game = version.arguments.game.iter().chain(&fabric.arguments.game);
    args.extend(game.flat_map(Argument::values).map(|arg| fill(arg, &vars)));

    let java = inside(&home.join("runtime"), &version.java_version.component)?.join(JAVA);
    let stderr = logs.join("stderr.log");
    Command::new(&java)
        .args(&args)
        .current_dir(&instance)
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(File::create(&stderr).map_err(io_error(&stderr))?)
        .spawn()
        .map_err(io_error(&java))
}

fn classpath(
    home: &Path,
    version: &Version,
    fabric: &FabricProfile,
    version_dir: &Path,
) -> Result<String> {
    let libraries = home.join("libraries");
    let mut entries = fabric
        .libraries
        .iter()
        .map(|library| inside(&libraries, &install::maven_path(&library.name)?))
        .collect::<Result<Vec<_>>>()?;
    for library in version
        .libraries
        .iter()
        .filter(|library| install::allowed(&library.rules))
    {
        if let Some(artifact) = &library.downloads.artifact {
            entries.push(inside(&libraries, &artifact.path)?);
        }
    }
    entries.push(version_dir.join(format!("{}.jar", version.id)));
    let joined =
        env::join_paths(&entries).map_err(|error| Error::Invalid(format!("classpath: {error}")))?;
    joined
        .into_string()
        .map_err(|path| Error::Invalid(format!("non-unicode classpath {}", path.display())))
}

fn text(path: &Path) -> Result<String> {
    path.to_str()
        .map(str::to_owned)
        .ok_or_else(|| Error::Invalid(format!("non-unicode path {}", path.display())))
}

fn fill(template: &str, vars: &HashMap<&str, String>) -> String {
    let mut filled = String::with_capacity(template.len());
    let mut rest = template;
    while let Some(start) = rest.find("${")
        && let Some(length) = rest[start..].find('}')
    {
        let end = start + length;
        filled.push_str(&rest[..start]);
        match vars.get(&rest[start + 2..end]) {
            Some(value) => filled.push_str(value),
            None => filled.push_str(&rest[start..=end]),
        }
        rest = &rest[end + 1..];
    }
    filled.push_str(rest);
    filled
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn placeholders_are_filled_once() {
        let vars = HashMap::from([
            ("name", "${name}".to_owned()),
            ("dir", "C:\\game".to_owned()),
        ]);
        assert_eq!(fill("--user ${name}", &vars), "--user ${name}");
        assert_eq!(
            fill("-Dpath=${dir}/x ${missing} ${dir", &vars),
            "-Dpath=C:\\game/x ${missing} ${dir"
        );
        assert_eq!(fill("plain", &vars), "plain");
    }
}
