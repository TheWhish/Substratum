#version 150

uniform sampler2D DarkSampler;
uniform sampler2D LitSampler;
uniform sampler2D DepthSampler;

uniform vec2 OutSize;
uniform vec4 Crop;
uniform vec2 Subject;
uniform vec3 Lamp;
uniform float Zoom;
uniform vec2 Look;
uniform float Glow;
uniform float Time;
uniform float Focus;
uniform float Far;

in vec2 texCoord;

out vec4 fragColor;

const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);
const float GOLDEN = 2.39996323;
const float BLUR = 0.006;
const float BLUR_STEPS = 14.0;
const float SHARP = 0.12;
const float BEHIND = 1.6;
const float AHEAD = 0.18;
const float HAZE = 0.035;
const float HAZE_GAIN = 0.35;
const float SHAFT_STEPS = 40.0;
const float SHAFT_SOURCE = 0.3;
const float SHAFT_REACH = 0.85;
const float SHAFT_DECAY = 0.95;
const float SHAFT_GAIN = 0.55;
const float HALATION = 0.22;
const vec3 HALATION_TINT = vec3(1.0, 0.42, 0.18);
const vec3 BALANCE = vec3(1.04, 1.0, 0.95);
const vec3 SHADOW_TINT = vec3(1.0, 0.99, 0.98);
const vec3 LIGHT_TINT = vec3(1.02, 1.0, 0.95);
const float EXPOSURE = 1.05;
const float CONTRAST = 1.06;
const float SATURATION = 0.94;
const vec3 LIFT = vec3(0.007, 0.006, 0.005);
const float VIGNETTE = 0.4;
const float GRAIN = 0.045;
const float DUST_LAYERS = 5.0;
const float DUST_GAIN = 1.8;
const float DUST_DENSITY = 0.84;
const float CONE_TOP = 0.06;
const float CONE_SPREAD = 0.7;
const float WARP_REACH = 0.024;
const float SWAY = 0.0028;
const float GLOW = 0.16;
const float GLOW_KNEE = 0.2;

vec3 linear(vec3 colour) {
    return pow(max(colour, 0.0), vec3(2.2));
}

vec3 radiance(vec2 uv) {
    return mix(linear(texture(DarkSampler, uv).rgb), linear(texture(LitSampler, uv).rgb), Glow);
}

vec3 blurred(vec2 uv, float level) {
    return mix(linear(textureLod(DarkSampler, uv, level).rgb), linear(textureLod(LitSampler, uv, level).rgb), Glow);
}

vec3 lampLight(vec2 uv, float level) {
    return max(linear(textureLod(LitSampler, uv, level).rgb) - linear(textureLod(DarkSampler, uv, level).rgb), 0.0) * Glow;
}

float decode(ivec2 texel, ivec2 last) {
    vec2 code = texelFetch(DepthSampler, clamp(texel, ivec2(0), last), 0).rg;
    return (code.r * 65280.0 + code.g * 255.0) / 65535.0 * Far;
}

float depthPoint(vec2 uv) {
    ivec2 size = textureSize(DepthSampler, 0);
    return max(decode(ivec2(uv * vec2(size)), size - 1), 0.05);
}

float depthAt(vec2 uv) {
    ivec2 size = textureSize(DepthSampler, 0);
    vec2 at = uv * vec2(size) - 0.5;
    ivec2 base = ivec2(floor(at));
    vec2 f = at - vec2(base);
    ivec2 last = size - 1;
    float top = mix(decode(base, last), decode(base + ivec2(1, 0), last), f.x);
    float bottom = mix(decode(base + ivec2(0, 1), last), decode(base + ivec2(1, 1), last), f.x);
    return max(mix(top, bottom, f.y), 0.05);
}

float hash(vec2 p) {
    uvec2 q = uvec2(ivec2(floor(p))) * uvec2(1597334673u, 3812015801u);
    uint n = (q.x ^ q.y) * 1597334673u;
    return float(n) / 4294967295.0;
}

vec2 hash2(vec2 p) {
    return vec2(hash(p), hash(p + vec2(71.0, 13.0)));
}

float nearness(vec2 uv) {
    float sum = 0.0;
    for (int x = -1; x <= 1; x++) {
        for (int y = -1; y <= 1; y++) {
            sum += 1.0 / depthAt(uv + vec2(x, y) * WARP_REACH);
        }
    }
    return sum / 9.0;
}

vec2 photo(vec2 screen) {
    vec2 uv = Crop.xy + vec2(screen.x, 1.0 - screen.y) * Crop.zw;
    return Subject + (uv - Subject) / Zoom;
}

float circle(float depth, float largest) {
    float behind = max(1.0 - Focus / depth - SHARP, 0.0) * BEHIND;
    float ahead = max(Focus / depth - 1.0 - SHARP, 0.0) * AHEAD;
    return largest * clamp(behind + ahead, 0.0, 1.0);
}

vec3 focused(vec2 uv, vec2 pixel, float depth) {
    float largest = BLUR * OutSize.y;
    float increment = largest / BLUR_STEPS;
    float size = circle(depth, largest);
    vec3 sum = radiance(uv);
    float count = 1.0;
    float radius = increment;
    float angle = 0.0;
    for (int i = 0; i < 256 && radius < largest; i++) {
        angle += GOLDEN;
        vec2 at = uv + vec2(cos(angle), sin(angle)) * pixel * radius;
        float far = depthPoint(at);
        float reach = circle(far, largest);
        if (far > depth) reach = min(reach, size * 2.0);
        sum += mix(sum / count, radiance(at), smoothstep(radius - 0.5, radius + 0.5, reach));
        count += 1.0;
        radius += increment / radius;
    }
    return sum / count;
}

vec3 shafts(vec2 uv) {
    if (Lamp.z < 0.5 || Glow <= 0.0) return vec3(0.0);
    vec2 delta = (Lamp.xy - uv) * SHAFT_REACH / SHAFT_STEPS;
    vec2 at = uv + delta * hash(gl_FragCoord.xy + 3.7);
    float weight = 1.0;
    vec3 sum = vec3(0.0);
    for (float i = 0.0; i < SHAFT_STEPS; i++) {
        sum += lampLight(at, 4.0) * (1.0 - smoothstep(0.0, SHAFT_SOURCE, distance(at, Lamp.xy))) * weight;
        weight *= SHAFT_DECAY;
        at += delta;
    }
    return sum * SHAFT_GAIN / SHAFT_STEPS;
}

vec3 dust(vec2 uv, float depth, vec2 pixel) {
    float below = uv.y - Lamp.y;
    float cone = Lamp.z * smoothstep(0.0, 0.04, below) * (1.0 - smoothstep(0.0, CONE_TOP + CONE_SPREAD * max(below, 0.0), abs(uv.x - Lamp.x)));
    vec3 beam = lampLight(uv, 6.0) * cone;
    if (dot(beam, LUMA) < 0.001) return vec3(0.0);
    vec2 frame = vec2(textureSize(DarkSampler, 0));
    float aspect = frame.x / frame.y;
    float largest = BLUR * OutSize.y;
    float sum = 0.0;
    for (float layer = 0.0; layer < DUST_LAYERS; layer++) {
        float near = Focus * (0.3 + 0.3 * layer);
        if (near >= depth) continue;
        float cell = 0.025 * Focus / near;
        vec2 drift = vec2(Time * 0.006 + sin(Time * 0.05 + layer * 1.7) * 0.2, Time * (0.004 + 0.002 * layer));
        vec2 grid = vec2(uv.x * aspect, uv.y) / cell + drift;
        vec2 id = floor(grid);
        vec2 seed = hash2(id + layer * 17.0);
        if (seed.x < DUST_DENSITY) continue;
        vec2 wander = 0.15 * vec2(sin(Time * 0.11 + seed.y * 6.2831853), cos(Time * 0.09 + seed.x * 6.2831853));
        vec2 centre = 0.2 + 0.6 * hash2(id + layer * 5.0 + 3.0) + wander;
        float radius = max(0.5 * Focus / near, 0.6) + circle(near, largest) * 0.8;
        float pixels = length((fract(grid) - centre) * cell) / pixel.y;
        sum += exp(-pixels * pixels / (radius * radius)) / max(radius * radius, 1.0) * (0.4 + 0.6 * seed.y);
    }
    return beam * sum * DUST_GAIN;
}

vec3 grade(vec3 colour, vec2 screen) {
    colour *= EXPOSURE * BALANCE;
    float luma = dot(colour, LUMA);
    colour = mix(vec3(luma), colour, SATURATION * (0.5 + 0.5 * smoothstep(0.0, 0.0015, luma)));
    colour *= mix(SHADOW_TINT, LIGHT_TINT, smoothstep(0.0, 0.35, luma));
    colour = colour / (1.0 + max(dot(colour, LUMA) - 0.8, 0.0));
    vec3 tone = pow(colour, vec3(1.0 / 2.2));
    tone = clamp((tone - 0.5) * CONTRAST + 0.5, 0.0, 1.0);
    vec2 centred = (screen - 0.5) * vec2(OutSize.x / OutSize.y, 1.0);
    tone *= mix(1.0, (1.0 - smoothstep(0.25, 1.25, length(centred))), VIGNETTE);
    tone += LIFT;
    float grain = hash(gl_FragCoord.xy + fract(floor(Time * 24.0) * 0.618) * 913.0) - 0.5;
    tone += grain * GRAIN * (1.0 - abs(dot(tone, LUMA) * 2.0 - 1.0) * 0.6);
    return tone + (hash(gl_FragCoord.xy * 1.37 + 5.0) - 0.5) / 255.0;
}

void main() {
    vec2 still = photo(texCoord);
    vec2 sway = SWAY * vec2(sin(Time * 0.13) + 0.5 * sin(Time * 0.31 + 1.0), cos(Time * 0.11) + 0.5 * sin(Time * 0.27 + 2.0));
    sway += Look;
    vec2 uv = clamp(still + sway * clamp(Focus * nearness(still) - 1.0, -1.0, 1.5), 0.0, 1.0);
    vec2 pixel = Crop.zw / (Zoom * OutSize);
    float depth = depthAt(uv);
    vec3 colour = focused(uv, pixel, depth);
    colour += (lampLight(uv, 6.0) * 0.8 + blurred(uv, 5.0) * 0.2) * (1.0 - exp(-depth * HAZE)) * HAZE_GAIN;
    colour += shafts(uv);
    colour += dust(uv, depth, pixel);
    vec3 bright = max(blurred(uv, 4.5) - 0.55, 0.0);
    colour += bright * HALATION_TINT * HALATION;
    colour += max(blurred(uv, 3.5) + blurred(uv, 5.5) - 2.0 * GLOW_KNEE, 0.0) * 0.5 * GLOW;
    fragColor = vec4(grade(colour, texCoord), 1.0);
}
