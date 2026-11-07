use std::collections::HashSet;
use std::fs;
use std::path::Path;

fn quoted(text: &str, prefix: &str) -> Vec<String> {
    text.split(prefix)
        .skip(1)
        .filter_map(|rest| rest.split_once('"').map(|(value, _)| value.to_owned()))
        .collect()
}

#[test]
fn every_interface_string_has_an_english_translation() {
    let root = Path::new(env!("CARGO_MANIFEST_DIR"));
    let po = fs::read_to_string(root.join("translations/en_US/LC_MESSAGES/substratum-launcher.po"))
        .unwrap();
    let translated: HashSet<String> = po
        .split("\nmsgid \"")
        .skip(1)
        .filter_map(|entry| {
            let (id, rest) = entry.split_once('"')?;
            let text = rest.split_once("msgstr \"")?.1.split_once('"')?.0;
            (!id.is_empty() && !text.is_empty()).then(|| id.to_owned())
        })
        .collect();
    let mut missing = Vec::new();
    for entry in fs::read_dir(root.join("ui")).unwrap() {
        let source = fs::read_to_string(entry.unwrap().path()).unwrap();
        missing.extend(
            quoted(&source, "@tr(\"")
                .into_iter()
                .filter(|id| !translated.contains(id)),
        );
    }
    assert!(missing.is_empty(), "untranslated: {missing:?}");
}
