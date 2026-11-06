use std::ops::RangeInclusive;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use slint::{Image, Rgba8Pixel, SharedPixelBuffer};

use crate::AppWindow;

pub const FRAME: Duration = Duration::from_millis(66);
pub const TRACKING: Duration = Duration::from_secs(4);

const NOISE_WIDTH: usize = 220;
const NOISE_HEIGHT: usize = 128;
const NOISE_SMEAR: u16 = 90;
const DROPOUT_CHANCE: u64 = 3;
const DROPOUT_LENGTH: RangeInclusive<u32> = 6..=40;
const BLINK: Duration = Duration::from_millis(1000);
const SCANLINE_ALPHA: u8 = 77;

const TRACKING_FRAMES: u32 = (TRACKING.as_millis() / FRAME.as_millis()) as u32 + 1;
const TRACKING_GAP: RangeInclusive<u32> = 105..=225;
const GLITCH_GAP: RangeInclusive<u32> = 300..=600;
const GLITCH_FRAMES: RangeInclusive<u32> = 1..=2;
const GLITCH_SHIFT: RangeInclusive<u32> = 2..=6;
const LOGO_GAP: RangeInclusive<u32> = 90..=180;
const LOGO_FRAMES: RangeInclusive<u32> = 1..=2;

pub fn scanlines() -> Image {
    let mut buffer = SharedPixelBuffer::<Rgba8Pixel>::new(1, 2);
    buffer.make_mut_slice()[0] = Rgba8Pixel::new(0, 0, 0, SCANLINE_ALPHA);
    Image::from_rgba8(buffer)
}

pub struct Tape {
    rng: Rng,
    started: Instant,
    grain: Vec<u8>,
    tracking: Effect,
    glitch: Effect,
    logo: Effect,
    shift: f32,
}

impl Tape {
    pub fn new() -> Self {
        let mut rng = Rng::seeded();
        let tracking = Effect::new(TRACKING_GAP, TRACKING_FRAMES..=TRACKING_FRAMES, &mut rng);
        let glitch = Effect::new(GLITCH_GAP, GLITCH_FRAMES, &mut rng);
        let logo = Effect::new(LOGO_GAP, LOGO_FRAMES, &mut rng);
        Self {
            rng,
            started: Instant::now(),
            grain: vec![128; NOISE_WIDTH * NOISE_HEIGHT],
            tracking,
            glitch,
            logo,
            shift: 0.0,
        }
    }

    pub fn advance(&mut self, app: &AppWindow) {
        let elapsed = self.started.elapsed();
        let seconds = i32::try_from(elapsed.as_secs()).unwrap_or(i32::MAX);
        if app.get_seconds() != seconds {
            app.set_seconds(seconds);
        }
        let blink = (elapsed.as_millis() / BLINK.as_millis()).is_multiple_of(2);
        if app.get_blink() != blink {
            app.set_blink(blink);
        }
        let tracking = self.tracking.tick(&mut self.rng);
        if app.get_tracking() != tracking {
            app.set_tracking(tracking);
        }
        let logo = self.logo.tick(&mut self.rng);
        if app.get_logo_glitch() != logo {
            app.set_logo_glitch(logo);
        }
        self.shift = match (self.glitch.tick(&mut self.rng), self.shift) {
            (false, _) => 0.0,
            (true, 0.0) => self.random_shift(),
            (true, shift) => shift,
        };
        if app.get_glitch_shift() != self.shift {
            app.set_glitch_shift(self.shift);
        }
        app.set_noise(self.noise());
    }

    fn noise(&mut self) -> Image {
        self.roll_grain();
        let mut buffer =
            SharedPixelBuffer::<Rgba8Pixel>::new(NOISE_WIDTH as u32, NOISE_HEIGHT as u32);
        for (pixel, &value) in buffer.make_mut_slice().iter_mut().zip(&self.grain) {
            *pixel = Rgba8Pixel::new(value, value, value, 255);
        }
        Image::from_rgba8(buffer)
    }

    fn roll_grain(&mut self) {
        for row in self.grain.chunks_mut(NOISE_WIDTH) {
            let mut smear = self.rng.byte();
            for cell in row {
                smear = (smear * NOISE_SMEAR + self.rng.byte() * (256 - NOISE_SMEAR)) / 256;
                *cell = ((u16::from(*cell) + smear) / 2) as u8;
            }
        }
        if self.rng.next().is_multiple_of(DROPOUT_CHANCE) {
            let row = self.rng.next() as usize % NOISE_HEIGHT;
            let length = self.rng.between(&DROPOUT_LENGTH) as usize;
            let start = self.rng.next() as usize % (NOISE_WIDTH - length);
            let line = row * NOISE_WIDTH + start;
            self.grain[line..line + length].fill(255);
        }
    }

    fn random_shift(&mut self) -> f32 {
        let magnitude = self.rng.between(&GLITCH_SHIFT) as f32;
        if self.rng.next() & 1 == 0 {
            magnitude
        } else {
            -magnitude
        }
    }
}
struct Effect {
    gap: RangeInclusive<u32>,
    length: RangeInclusive<u32>,
    wait: u32,
    left: u32,
}

impl Effect {
    fn new(gap: RangeInclusive<u32>, length: RangeInclusive<u32>, rng: &mut Rng) -> Self {
        let wait = rng.between(&gap);
        Self {
            gap,
            length,
            wait,
            left: 0,
        }
    }

    fn tick(&mut self, rng: &mut Rng) -> bool {
        if self.left > 0 {
            self.left -= 1;
            return true;
        }
        if self.wait > 0 {
            self.wait -= 1;
            return false;
        }
        self.left = rng.between(&self.length) - 1;
        self.wait = rng.between(&self.gap);
        true
    }
}

pub struct Rng(u64);

impl Rng {
    pub fn seeded() -> Self {
        let nanos = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map_or(0, |elapsed| elapsed.as_nanos() as u64);
        Self(nanos | 1)
    }

    fn byte(&mut self) -> u16 {
        (self.next() >> 56) as u16
    }

    pub fn next(&mut self) -> u64 {
        let mut x = self.0;
        x ^= x >> 12;
        x ^= x << 25;
        x ^= x >> 27;
        self.0 = x;
        x.wrapping_mul(0x2545_F491_4F6C_DD1D)
    }

    fn between(&mut self, range: &RangeInclusive<u32>) -> u32 {
        let span = u64::from(range.end() - range.start()) + 1;
        range.start() + (self.next() % span) as u32
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn effect_runs_for_its_length_then_waits() {
        let mut rng = Rng(7);
        let mut effect = Effect {
            gap: 3..=3,
            length: 2..=2,
            wait: 1,
            left: 0,
        };
        let frames: Vec<bool> = (0..8).map(|_| effect.tick(&mut rng)).collect();
        assert_eq!(frames, [false, true, true, false, false, false, true, true]);
    }

    #[test]
    fn between_stays_inside_the_range() {
        let mut rng = Rng(42);
        assert!((0..10_000).all(|_| GLITCH_SHIFT.contains(&rng.between(&GLITCH_SHIFT))));
    }

    #[test]
    fn grain_stays_mid_grey_and_streaks_horizontally() {
        let mut tape = Tape::new();
        for _ in 0..30 {
            tape.roll_grain();
        }
        let grain = &tape.grain;
        let mean = grain.iter().map(|&v| u64::from(v)).sum::<u64>() / grain.len() as u64;
        assert!((100..=156).contains(&mean), "mean {mean}");
        let along = |step: usize| -> u64 {
            (0..grain.len() - NOISE_WIDTH)
                .filter(|i| !(i + step).is_multiple_of(NOISE_WIDTH) || step == NOISE_WIDTH)
                .map(|i| u64::from(grain[i].abs_diff(grain[i + step])))
                .sum()
        };
        assert!(along(1) < along(NOISE_WIDTH));
    }

    #[test]
    fn tracking_frames_cover_the_animation() {
        assert!(FRAME * TRACKING_FRAMES >= TRACKING);
    }
}
