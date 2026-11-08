use std::io::Cursor;

use slint::{Image, Rgba8Pixel, SharedPixelBuffer};

use crate::fetch::{Error, Result};

pub const WIDTH: u32 = 128;
pub const HEIGHT: u32 = 64;
const DECODE_LIMIT: usize = 1024 * 1024;
const FACE: (u32, u32, u32) = (8, 8, 8);

pub struct Preset {
    pub name: &'static str,
    png: &'static [u8],
}

macro_rules! preset {
    ($name:literal) => {
        Preset {
            name: $name,
            png: include_bytes!(concat!(
                "../../game/common/src/main/resources/assets/substratum/textures/skin/",
                $name,
                ".png"
            )),
        }
    };
}

pub const PRESETS: [Preset; 4] = [
    preset!("yellow"),
    preset!("green"),
    preset!("red"),
    preset!("blue"),
];

#[derive(Clone)]
pub struct Skin(Vec<u8>);

impl Skin {
    pub fn preset(preset: &Preset) -> Result<Skin> {
        Skin::decode(preset.png)
            .map_err(|reason| Error::Invalid(format!("skin {}: {reason}", preset.name)))
    }

    fn decode(bytes: &[u8]) -> std::result::Result<Skin, String> {
        let mut decoder = png::Decoder::new_with_limits(
            Cursor::new(bytes),
            png::Limits {
                bytes: DECODE_LIMIT,
            },
        );
        decoder.set_transformations(png::Transformations::normalize_to_color8());
        let mut reader = decoder.read_info().map_err(|error| error.to_string())?;
        let (width, height) = (reader.info().width, reader.info().height);
        if (width, height) != (WIDTH, HEIGHT) {
            return Err(format!("expected {WIDTH}x{HEIGHT}, got {width}x{height}"));
        }
        let mut buffer = vec![0; reader.output_buffer_size().ok_or("image is too large")?];
        let frame = reader
            .next_frame(&mut buffer)
            .map_err(|error| error.to_string())?;
        let pixels = &buffer[..frame.buffer_size()];
        let rgba = match frame.color_type {
            png::ColorType::Rgba => pixels.to_vec(),
            png::ColorType::Rgb => pixels
                .as_chunks::<3>()
                .0
                .iter()
                .flat_map(|&[r, g, b]| [r, g, b, 255])
                .collect(),
            png::ColorType::GrayscaleAlpha => pixels
                .as_chunks::<2>()
                .0
                .iter()
                .flat_map(|&[gray, alpha]| [gray, gray, gray, alpha])
                .collect(),
            png::ColorType::Grayscale => pixels.iter().flat_map(|&g| [g, g, g, 255]).collect(),
            png::ColorType::Indexed => return Err("palette was not expanded".to_owned()),
        };
        Ok(Skin(rgba))
    }

    pub fn face(&self) -> Image {
        let (left, top, side) = FACE;
        let mut buffer = SharedPixelBuffer::<Rgba8Pixel>::new(side, side);
        for (index, pixel) in buffer.make_mut_slice().iter_mut().enumerate() {
            let [r, g, b, _] = self.pixel(left + index as u32 % side, top + index as u32 / side);
            *pixel = Rgba8Pixel { r, g, b, a: 255 };
        }
        Image::from_rgba8(buffer)
    }

    pub fn texel(&self, u: f32, v: f32) -> [u8; 4] {
        let column = ((u * WIDTH as f32) as u32).min(WIDTH - 1);
        let row = ((v * HEIGHT as f32) as u32).min(HEIGHT - 1);
        self.pixel(column, row)
    }

    fn pixel(&self, x: u32, y: u32) -> [u8; 4] {
        let at = ((y * WIDTH + x) * 4) as usize;
        [self.0[at], self.0[at + 1], self.0[at + 2], self.0[at + 3]]
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn presets_are_suit_textures_without_soft_edges() {
        for preset in &PRESETS {
            let skin = Skin::preset(preset).unwrap();
            assert!(
                skin.0
                    .as_chunks::<4>()
                    .0
                    .iter()
                    .all(|pixel| pixel[3] == 0 || pixel[3] == 255),
                "{} has translucent texels the preview draws as opaque",
                preset.name
            );
        }
    }

    #[test]
    fn foreign_sizes_and_garbage_are_rejected() {
        assert!(Skin::decode(b"not a png").is_err());
        let mut bytes = Vec::new();
        let mut encoder = png::Encoder::new(&mut bytes, 64, 64);
        encoder.set_color(png::ColorType::Rgba);
        let mut writer = encoder.write_header().unwrap();
        writer.write_image_data(&[0; 64 * 64 * 4]).unwrap();
        writer.finish().unwrap();
        assert!(Skin::decode(&bytes).is_err());
    }
}
