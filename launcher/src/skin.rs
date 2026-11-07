use std::io::Cursor;

use slint::{Image, Rgba8Pixel, SharedPixelBuffer};

use crate::fetch::{Error, Result};

const SIDE: u32 = 64;
const DECODE_LIMIT: usize = 1024 * 1024;
const OPAQUE: [Area; 3] = [(0, 0, 32, 16), (0, 16, 64, 16), (16, 48, 32, 16)];

type Area = (u32, u32, u32, u32);
type Piece = (u32, u32, u32, u32, u32, u32);

pub struct Preset {
    pub name: &'static str,
    png: &'static [u8],
}

pub const PRESETS: [Preset; 4] = [
    Preset {
        name: "yellow",
        png: include_bytes!("../../game/common/src/main/resources/assets/substratum/textures/skin/yellow.png"),
    },
    Preset {
        name: "green",
        png: include_bytes!("../../game/common/src/main/resources/assets/substratum/textures/skin/green.png"),
    },
    Preset {
        name: "red",
        png: include_bytes!("../../game/common/src/main/resources/assets/substratum/textures/skin/red.png"),
    },
    Preset {
        name: "blue",
        png: include_bytes!("../../game/common/src/main/resources/assets/substratum/textures/skin/blue.png"),
    },
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
        if (width, height) != (SIDE, SIDE) {
            return Err(format!("expected {SIDE}x{SIDE}, got {width}x{height}"));
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
        let mut skin = Skin(rgba);
        for area in OPAQUE {
            skin.opaque(area);
        }
        Ok(skin)
    }

    pub fn front(&self) -> Image {
        self.view(
            16,
            32,
            &[
                (8, 8, 8, 8, 4, 0),
                (20, 20, 8, 12, 4, 8),
                (44, 20, 4, 12, 0, 8),
                (36, 52, 4, 12, 12, 8),
                (4, 20, 4, 12, 4, 20),
                (20, 52, 4, 12, 8, 20),
            ],
            &[
                (40, 8, 8, 8, 4, 0),
                (20, 36, 8, 12, 4, 8),
                (44, 36, 4, 12, 0, 8),
                (52, 52, 4, 12, 12, 8),
                (4, 36, 4, 12, 4, 20),
                (4, 52, 4, 12, 8, 20),
            ],
        )
    }

    pub fn back(&self) -> Image {
        self.view(
            16,
            32,
            &[
                (24, 8, 8, 8, 4, 0),
                (32, 20, 8, 12, 4, 8),
                (44, 52, 4, 12, 0, 8),
                (52, 20, 4, 12, 12, 8),
                (28, 52, 4, 12, 4, 20),
                (12, 20, 4, 12, 8, 20),
            ],
            &[
                (56, 8, 8, 8, 4, 0),
                (32, 36, 8, 12, 4, 8),
                (60, 52, 4, 12, 0, 8),
                (52, 36, 4, 12, 12, 8),
                (12, 52, 4, 12, 4, 20),
                (12, 36, 4, 12, 8, 20),
            ],
        )
    }

    pub fn face(&self) -> Image {
        self.view(8, 8, &[(8, 8, 8, 8, 0, 0)], &[(40, 8, 8, 8, 0, 0)])
    }

    fn view(&self, width: u32, height: u32, base: &[Piece], overlay: &[Piece]) -> Image {
        let mut buffer = SharedPixelBuffer::<Rgba8Pixel>::new(width, height);
        let pixels = buffer.make_mut_slice();
        let layers = base
            .iter()
            .map(|piece| (piece, true))
            .chain(overlay.iter().map(|piece| (piece, false)));
        for (&(sx, sy, w, h, dx, dy), solid) in layers {
            for y in 0..h {
                for x in 0..w {
                    let [r, g, b, a] = self.pixel(sx + x, sy + y);
                    let target = &mut pixels[((dy + y) * width + dx + x) as usize];
                    if solid || a == 255 {
                        *target = Rgba8Pixel { r, g, b, a: 255 };
                    } else if a > 0 {
                        *target = blend(*target, [r, g, b, a]);
                    }
                }
            }
        }
        Image::from_rgba8(buffer)
    }

    fn pixel(&self, x: u32, y: u32) -> [u8; 4] {
        let at = ((y * SIDE + x) * 4) as usize;
        [self.0[at], self.0[at + 1], self.0[at + 2], self.0[at + 3]]
    }

    fn opaque(&mut self, (x, y, w, h): Area) {
        for row in y..y + h {
            for column in x..x + w {
                self.0[((row * SIDE + column) * 4 + 3) as usize] = 255;
            }
        }
    }
}

fn blend(under: Rgba8Pixel, [r, g, b, a]: [u8; 4]) -> Rgba8Pixel {
    let mix = |top: u8, bottom: u8| {
        ((top as u16 * a as u16 + bottom as u16 * (255 - a as u16)) / 255) as u8
    };
    Rgba8Pixel {
        r: mix(r, under.r),
        g: mix(g, under.g),
        b: mix(b, under.b),
        a: under.a.max(a),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn presets_decode_with_a_transparent_overlay_gap() {
        for preset in &PRESETS {
            let skin = Skin::preset(preset).unwrap();
            assert_eq!(skin.pixel(42, 10)[3], 0, "{}", preset.name);
        }
    }

    #[test]
    fn foreign_sizes_and_garbage_are_rejected() {
        assert!(Skin::decode(b"not a png").is_err());
        let mut bytes = Vec::new();
        let mut encoder = png::Encoder::new(&mut bytes, 64, 32);
        encoder.set_color(png::ColorType::Rgba);
        let mut writer = encoder.write_header().unwrap();
        writer.write_image_data(&[0; 64 * 32 * 4]).unwrap();
        writer.finish().unwrap();
        assert!(Skin::decode(&bytes).is_err());
    }

    #[test]
    fn base_layers_become_opaque() {
        let mut bytes = Vec::new();
        let mut encoder = png::Encoder::new(&mut bytes, 64, 64);
        encoder.set_color(png::ColorType::Rgba);
        let mut writer = encoder.write_header().unwrap();
        writer.write_image_data(&[0; 64 * 64 * 4]).unwrap();
        writer.finish().unwrap();
        let skin = Skin::decode(&bytes).unwrap();
        assert_eq!(skin.pixel(8, 8)[3], 255);
        assert_eq!(skin.pixel(40, 8)[3], 0);
    }
}
