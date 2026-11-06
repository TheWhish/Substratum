use slint::{ModelRc, SharedString, VecModel};

use crate::vhs::Rng;

pub const LENGTH: usize = 8;
pub const ALPHABET: &[u8] = b"ABCDEFGHJKMNPQRSTUVWXYZ23456789";

pub fn generate(rng: &mut Rng) -> String {
    (0..LENGTH)
        .map(|_| ALPHABET[(rng.next() % ALPHABET.len() as u64) as usize] as char)
        .collect()
}

pub fn sanitize(input: &str) -> String {
    input
        .chars()
        .map(|c| c.to_ascii_uppercase())
        .filter(|c| c.is_ascii() && ALPHABET.contains(&(*c as u8)))
        .take(LENGTH)
        .collect()
}

pub fn cells(code: &str) -> ModelRc<SharedString> {
    let mut chars = code.chars();
    let cells: Vec<SharedString> = (0..LENGTH)
        .map(|_| {
            chars
                .next()
                .map(|c| c.to_string().into())
                .unwrap_or_default()
        })
        .collect();
    ModelRc::new(VecModel::from(cells))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn sanitize_uppercases_drops_lookalikes_and_truncates() {
        assert_eq!(sanitize("k7q2-9mxa"), "K7Q29MXA");
        assert_eq!(sanitize("0O1IL"), "");
        assert_eq!(sanitize("ABCDEFGHJK"), "ABCDEFGH");
        assert_eq!(sanitize("ж z"), "Z");
    }

    #[test]
    fn cells_pad_to_full_length() {
        use slint::Model;
        let model = cells("AB");
        assert_eq!(model.row_count(), LENGTH);
        assert_eq!(model.row_data(1).as_deref(), Some("B"));
        assert_eq!(model.row_data(2).as_deref(), Some(""));
    }
}
