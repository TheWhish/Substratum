use std::collections::HashMap;
use std::env;
use std::fs::{self, File};
use std::io;
use std::path::{Path, PathBuf};
use std::process::{Child, Command, Stdio};

use crate::fetch::{Error, Result, inside, io_error};
use crate::install::{self, Argument, FabricProfile, Version};
use crate::profile::Profile;
use crate::settings::Settings;

const JAVA: &str = "bin/javaw.exe";
const LAUNCHER_NAME: &str = "substratum-launcher";

pub fn instance(home: &Path) -> PathBuf {
    home.join("instance")
}

pub fn start(
    home: &Path,
    profile: &Profile,
    settings: &Settings,
    heap: u32,
    link: u16,
) -> Result<Child> {
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

    let mut args = vec![
        format!("-Xmx{heap}G"),
        format!("-Dsubstratum.skin={}", profile.skin),
        format!("-Dsubstratum.link={link}"),
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
    if let Some((width, height)) = settings.window.size() {
        args.extend([
            "--width".to_owned(),
            width.to_string(),
            "--height".to_owned(),
            height.to_string(),
        ]);
    }
    let fullscreen = settings.window.size().is_none().to_string();
    write_options(
        &instance.join("options.txt"),
        &[
            ("fullscreen", &fullscreen),
            ("lang", settings.language.minecraft()),
            ("onboardAccessibility", "false"),
            ("tutorialStep", "none"),
        ],
    )?;

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

fn write_options(path: &Path, values: &[(&str, &str)]) -> Result<()> {
    let existing = match fs::read_to_string(path) {
        Ok(text) => text,
        Err(error) if error.kind() == io::ErrorKind::NotFound => String::new(),
        Err(error) => {
            return Err(Error::Io {
                path: path.to_path_buf(),
                source: error,
            });
        }
    };
    let replaced = |line: &&str| {
        values
            .iter()
            .any(|(key, _)| line.split_once(':').is_some_and(|(name, _)| name == *key))
    };
    let mut lines: Vec<String> = existing
        .lines()
        .filter(|line| !replaced(line))
        .map(str::to_owned)
        .collect();
    lines.extend(values.iter().map(|(key, value)| format!("{key}:{value}")));
    install::save(
        path,
        format!(
            "{}
",
            lines.join(
                "
"
            )
        )
        .as_bytes(),
    )
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
    fn options_keep_unrelated_lines() {
        let path =
            std::env::temp_dir().join(format!("substratum-options-{}.txt", std::process::id()));
        fs::write(
            &path,
            "version:3955
lang:en_us
fov:0.5
fullscreen:false
",
        )
        .unwrap();
        write_options(&path, &[("fullscreen", "true"), ("lang", "ru_ru")]).unwrap();
        assert_eq!(
            fs::read_to_string(&path).unwrap(),
            "version:3955
fov:0.5
fullscreen:true
lang:ru_ru
"
        );
        fs::remove_file(&path).unwrap();
    }

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
