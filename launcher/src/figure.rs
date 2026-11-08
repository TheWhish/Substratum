use std::cell::RefCell;

use serde::Deserialize;
use slint::{Image, Rgba8Pixel, SharedPixelBuffer};

use crate::skin::Skin;

const SUIT: &str = include_str!("../assets/suit.json");
const LIGHT_POWER: f32 = 0.6;
const AMBIENT: f32 = 0.4;
const ALPHA_CUTOFF: u8 = 26;
const SAMPLES: u32 = 3;
const LARGEST: u32 = 2048;
const DISTANCE: f32 = 9.0;
const MARGIN: f32 = 0.92;
pub const TILT_LIMIT: f32 = 0.35;
const BOB_RATE: f32 = 0.09;
const BOB_SWAY_RATE: f32 = 0.067;
const BOB: f32 = 0.05;
const BREATH_RATE: f32 = 0.1;
const BREATH: f32 = 0.08 / 16.0;

type Vec3 = [f32; 3];

#[derive(Deserialize)]
struct Data {
    width: u32,
    height: u32,
    parts: Vec<PartData>,
}

#[derive(Deserialize)]
struct PartData {
    path: String,
    pivot: Vec3,
    rotation: Vec3,
    quads: Vec<[f32; 23]>,
}

struct Part {
    parent: Option<usize>,
    motion: Motion,
    pivot: Vec3,
    rotation: Vec3,
    quads: Vec<[f32; 23]>,
}

#[derive(Clone, Copy, PartialEq)]
enum Motion {
    Still,
    Bob(f32),
    Breath,
}

#[derive(Clone, Copy)]
struct Affine {
    axes: [Vec3; 3],
    origin: Vec3,
}

impl Affine {
    const FLIP: Affine = Affine {
        axes: [[-1.0, 0.0, 0.0], [0.0, -1.0, 0.0], [0.0, 0.0, 1.0]],
        origin: [0.0; 3],
    };

    fn posed(pivot: Vec3, [x, y, z]: Vec3) -> Affine {
        let (sx, cx) = x.sin_cos();
        let (sy, cy) = y.sin_cos();
        let (sz, cz) = z.sin_cos();
        Affine {
            axes: [
                [cz * cy, sz * cy, -sy],
                [cz * sy * sx - sz * cx, sz * sy * sx + cz * cx, cy * sx],
                [cz * sy * cx + sz * sx, sz * sy * cx - cz * sx, cy * cx],
            ],
            origin: pivot,
        }
    }

    fn turn(&self, v: Vec3) -> Vec3 {
        let [a, b, c] = self.axes;
        [
            a[0] * v[0] + b[0] * v[1] + c[0] * v[2],
            a[1] * v[0] + b[1] * v[1] + c[1] * v[2],
            a[2] * v[0] + b[2] * v[1] + c[2] * v[2],
        ]
    }

    fn apply(&self, v: Vec3) -> Vec3 {
        let [x, y, z] = self.turn(v);
        [x + self.origin[0], y + self.origin[1], z + self.origin[2]]
    }

    fn then(&self, inner: &Affine) -> Affine {
        Affine {
            axes: inner.axes.map(|axis| self.turn(axis)),
            origin: self.apply(inner.origin),
        }
    }
}

pub struct View {
    pub yaw: f32,
    pub tilt: f32,
    pub age: f32,
    pub width: u32,
    pub height: u32,
}

pub struct Figure {
    canvas: RefCell<Canvas>,
    parts: Vec<Part>,
    lights: [Vec3; 2],
    middle: f32,
    half_height: f32,
    reach: f32,
}

impl Figure {
    pub fn load() -> Result<Figure, String> {
        let data: Data = serde_json::from_str(SUIT).map_err(|error| error.to_string())?;
        if (data.width, data.height) != (crate::skin::WIDTH, crate::skin::HEIGHT) {
            return Err(format!("suit texture is {}x{}", data.width, data.height));
        }
        let mut parts: Vec<Part> = Vec::with_capacity(data.parts.len());
        for part in &data.parts {
            let above = part.path.rsplit_once('/').map_or("", |(above, _)| above);
            let parent = match above {
                "" => None,
                above => Some(
                    data.parts
                        .iter()
                        .take(parts.len())
                        .position(|other| other.path == above)
                        .ok_or_else(|| format!("{} comes before its parent", part.path))?,
                ),
            };
            parts.push(Part {
                parent,
                motion: match part.path.as_str() {
                    "/right_arm" => Motion::Bob(1.0),
                    "/left_arm" => Motion::Bob(-1.0),
                    "/body/pack" => Motion::Breath,
                    _ => Motion::Still,
                },
                pivot: part.pivot,
                rotation: part.rotation,
                quads: part.quads.clone(),
            });
        }
        if parts
            .iter()
            .filter(|part| part.motion != Motion::Still)
            .count()
            != 3
        {
            return Err("suit lost an animated part".to_owned());
        }
        let mut figure = Figure {
            canvas: RefCell::new(Canvas::default()),
            parts,
            lights: [normalize([0.2, 1.0, -0.7]), normalize([-0.2, 1.0, 0.7])],
            middle: 0.0,
            half_height: 0.0,
            reach: 0.0,
        };
        let (mut low, mut high, mut reach) = (f32::MAX, f32::MIN, 0f32);
        for (place, part) in figure.poses(0.0).iter().zip(&figure.parts) {
            for quad in &part.quads {
                for corner in quad[3..].as_chunks::<5>().0 {
                    let [x, y, z] = place.apply([corner[0], corner[1], corner[2]]);
                    low = low.min(y);
                    high = high.max(y);
                    reach = reach.max(x.hypot(z));
                }
            }
        }
        figure.middle = (low + high) / 2.0;
        figure.half_height = (high - low) / 2.0;
        figure.reach = reach;
        Ok(figure)
    }

    fn poses(&self, age: f32) -> Vec<Affine> {
        let mut poses: Vec<Affine> = Vec::with_capacity(self.parts.len());
        for part in &self.parts {
            let (mut pivot, mut rotation) = (part.pivot, part.rotation);
            match part.motion {
                Motion::Bob(side) => {
                    rotation[2] += side * ((age * BOB_RATE).cos() * BOB + BOB);
                    rotation[0] += side * (age * BOB_SWAY_RATE).sin() * BOB;
                }
                Motion::Breath => pivot[1] += (age * BREATH_RATE).sin() * BREATH,
                Motion::Still => {}
            }
            let outer = part.parent.map_or(Affine::FLIP, |parent| poses[parent]);
            poses.push(outer.then(&Affine::posed(pivot, rotation)));
        }
        poses
    }

    pub fn render(&self, skin: &Skin, view: &View) -> Image {
        let (width, height) = (view.width.clamp(1, LARGEST), view.height.clamp(1, LARGEST));
        let (wide, tall) = (width * SAMPLES, height * SAMPLES);
        let tilt = view.tilt.clamp(-TILT_LIMIT, TILT_LIMIT);
        let span = self.half_height * TILT_LIMIT.cos() + self.reach * TILT_LIMIT.sin();
        let focal = (tall as f32 / 2.0 * MARGIN * DISTANCE / span)
            .min(wide as f32 / 2.0 * MARGIN * (DISTANCE - self.reach) / self.reach);
        let camera = Affine::posed([0.0; 3], [-tilt, 0.0, 0.0])
            .then(&Affine::posed([0.0; 3], [0.0, view.yaw, 0.0]));
        let mut faces = Vec::new();
        for (place, part) in self.poses(view.age).iter().zip(&self.parts) {
            for quad in &part.quads {
                let normal = normalize(place.turn([quad[0], quad[1], quad[2]]));
                let light = self
                    .lights
                    .iter()
                    .map(|light| dot(*light, normal).max(0.0))
                    .sum::<f32>();
                let shade = (light * LIGHT_POWER + AMBIENT).min(1.0);
                let corners: [Corner; 4] = std::array::from_fn(|index| {
                    let at = &quad[3 + index * 5..8 + index * 5];
                    let [x, y, z] = place.apply([at[0], at[1], at[2]]);
                    let [x, y, z] = camera.turn([x, y - self.middle, z]);
                    let depth = DISTANCE + z;
                    Corner {
                        x: wide as f32 / 2.0 - focal * x / depth,
                        y: tall as f32 / 2.0 - focal * y / depth,
                        near: 1.0 / depth,
                        u: at[3],
                        v: at[4],
                    }
                });
                let nearest = corners.iter().map(|corner| corner.near).fold(0.0, f32::max);
                faces.push((nearest, shade, corners));
            }
        }
        faces.sort_unstable_by(|a, b| b.0.total_cmp(&a.0));
        let mut canvas = self.canvas.borrow_mut();
        canvas.reset(wide, tall);
        for (_, shade, corners) in &faces {
            canvas.quad(skin, *shade, corners);
        }
        canvas.resolve(width, height)
    }
}

struct Corner {
    x: f32,
    y: f32,
    near: f32,
    u: f32,
    v: f32,
}

#[derive(Clone, Copy)]
struct Plane {
    origin: f32,
    dx: f32,
    dy: f32,
}

impl Plane {
    fn row(&self, y: f32) -> (f32, f32) {
        (self.origin + self.dy * y, self.dx)
    }
}

#[derive(Default)]
struct Canvas {
    width: u32,
    height: u32,
    color: Vec<[u8; 4]>,
    depth: Vec<f32>,
}

impl Canvas {
    fn reset(&mut self, width: u32, height: u32) {
        let size = (width * height) as usize;
        (self.width, self.height) = (width, height);
        self.color.clear();
        self.color.resize(size, [0; 4]);
        self.depth.clear();
        self.depth.resize(size, 0.0);
    }

    fn quad(&mut self, skin: &Skin, shade: f32, corners: &[Corner; 4]) {
        let light = (shade * 256.0) as u32;
        let lit = |channel: u8| ((channel as u32 * light + 128) >> 8) as u8;
        let [a, b, c, _] = corners;
        let area = edge(a, b, c.x, c.y);
        if area.abs() < f32::EPSILON {
            return;
        }
        let sign = area.signum();
        let edges: [Plane; 4] = std::array::from_fn(|index| {
            let (from, to) = (&corners[index], &corners[(index + 1) % 4]);
            let (dx, dy) = (sign * (from.y - to.y), sign * (to.x - from.x));
            Plane {
                origin: -dx * from.x - dy * from.y,
                dx,
                dy,
            }
        });
        let attribute = |at: fn(&Corner) -> f32| {
            let (fa, fb, fc) = (at(a), at(b), at(c));
            let dx = ((fb - fa) * (c.y - a.y) + (fc - fa) * (a.y - b.y)) / area;
            let dy = ((fb - fa) * (a.x - c.x) + (fc - fa) * (b.x - a.x)) / area;
            Plane {
                origin: fa - dx * a.x - dy * a.y,
                dx,
                dy,
            }
        };
        let near = attribute(|corner| corner.near);
        let u = attribute(|corner| corner.u * corner.near);
        let v = attribute(|corner| corner.v * corner.near);
        let xs = corners.iter().map(|corner| corner.x);
        let ys = corners.iter().map(|corner| corner.y);
        let left = xs.clone().fold(f32::MAX, f32::min).floor().max(0.0) as u32;
        let right = (xs.fold(f32::MIN, f32::max).ceil() as u32).min(self.width);
        let top = ys.clone().fold(f32::MAX, f32::min).floor().max(0.0) as u32;
        let bottom = (ys.fold(f32::MIN, f32::max).ceil() as u32).min(self.height);
        for row in top..bottom {
            let py = row as f32 + 0.5;
            let Some(columns) = span(edges.map(|plane| plane.row(py)), left, right) else {
                continue;
            };
            let (near_row, u_row, v_row) = (near.row(py), u.row(py), v.row(py));
            for column in columns {
                let px = column as f32 + 0.5;
                let depth = near_row.0 + near_row.1 * px;
                let at = (row * self.width + column) as usize;
                if depth <= self.depth[at] {
                    continue;
                }
                let far = 1.0 / depth;
                let texel = skin.texel(
                    (u_row.0 + u_row.1 * px) * far,
                    (v_row.0 + v_row.1 * px) * far,
                );
                if texel[3] < ALPHA_CUTOFF {
                    continue;
                }
                self.depth[at] = depth;
                self.color[at] = [lit(texel[0]), lit(texel[1]), lit(texel[2]), 255];
            }
        }
    }

    fn resolve(&self, width: u32, height: u32) -> Image {
        let mut buffer = SharedPixelBuffer::<Rgba8Pixel>::new(width, height);
        let count = SAMPLES * SAMPLES;
        for (index, pixel) in buffer.make_mut_slice().iter_mut().enumerate() {
            let (x, y) = (
                index as u32 % width * SAMPLES,
                index as u32 / width * SAMPLES,
            );
            let mut sum = [0u32; 4];
            for dy in 0..SAMPLES {
                for dx in 0..SAMPLES {
                    let sample = self.color[((y + dy) * self.width + x + dx) as usize];
                    for (total, value) in sum.iter_mut().zip(sample) {
                        *total += value as u32;
                    }
                }
            }
            let covered = sum[3] / 255;
            let mean = |total: u32| (total + covered / 2).checked_div(covered).unwrap_or(0) as u8;
            *pixel = Rgba8Pixel {
                r: mean(sum[0]),
                g: mean(sum[1]),
                b: mean(sum[2]),
                a: ((sum[3] + count / 2) / count) as u8,
            };
        }
        Image::from_rgba8(buffer)
    }
}

fn span(rows: [(f32, f32); 4], left: u32, right: u32) -> Option<std::ops::Range<u32>> {
    let (mut from, mut to) = (left as f32, right as f32);
    for (base, step) in rows {
        let cross = -base / step - 0.5;
        if step > 0.0 {
            from = from.max(cross.ceil());
        } else if step < 0.0 {
            to = to.min(cross.floor() + 1.0);
        } else if base < 0.0 {
            return None;
        }
    }
    (from < to).then_some(from as u32..to as u32)
}

fn edge(from: &Corner, to: &Corner, x: f32, y: f32) -> f32 {
    (to.x - from.x) * (y - from.y) - (to.y - from.y) * (x - from.x)
}

fn dot(a: Vec3, b: Vec3) -> f32 {
    a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
}

fn normalize(v: Vec3) -> Vec3 {
    let length = dot(v, v).sqrt();
    v.map(|value| value / length)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::skin::PRESETS;

    #[test]
    fn every_turn_and_tilt_stays_inside_the_frame() {
        let figure = Figure::load().unwrap();
        let skin = Skin::preset(&PRESETS[0]).unwrap();
        for step in 0..16 {
            for tilt in [-TILT_LIMIT, 0.0, TILT_LIMIT] {
                let view = View {
                    yaw: step as f32 * 0.4,
                    tilt,
                    age: step as f32 * 7.0,
                    width: 120,
                    height: 200,
                };
                let image = figure.render(&skin, &view);
                let pixels = image.to_rgba8().unwrap();
                let alpha = |x: u32, y: u32| pixels.as_slice()[(y * 120 + x) as usize].a;
                let border = (0..120)
                    .flat_map(|x| [(x, 0), (x, 199)])
                    .chain((0..200).flat_map(|y| [(0, y), (119, y)]));
                assert!(
                    border.into_iter().all(|(x, y)| alpha(x, y) == 0),
                    "clipped at yaw step {step}, tilt {tilt}"
                );
                let covered = pixels.as_slice().iter().filter(|pixel| pixel.a > 0).count();
                assert!(
                    covered > 120 * 200 / 8,
                    "figure vanished at yaw step {step}, tilt {tilt}"
                );
            }
        }
    }
}
