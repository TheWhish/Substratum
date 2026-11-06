#version 150

uniform sampler2D DiffuseSampler;
uniform sampler2D BloomSampler;
uniform sampler2D LightSampler;
uniform sampler2D DepthSampler;
uniform vec2 InSize;
uniform float Time;
uniform float Strength;
uniform float Bloom;
uniform float Shoulder;
uniform float Night;
uniform float Vignette;
uniform float Grain;
uniform float Aberration;
uniform float Ghost;
uniform float Tunnel;
uniform float Drain;
uniform float Lit;
uniform float Hands;
uniform float Haze;
uniform float Vhs;
uniform float VhsSeed;
uniform float VhsShake;

in vec2 texCoord;

out vec4 fragColor;

const int SPECTRUM = 7;
const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);
const vec3 HALATION = vec3(1.0, 0.93, 0.8);

float hash(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

vec3 fetch(vec2 uv) {
    return pow(texture(DiffuseSampler, uv).rgb, vec3(2.2));
}

vec3 split(vec2 uv, vec2 shift) {
    vec3 sum = vec3(0.0);
    vec3 total = vec3(0.0);
    for (int i = 0; i < SPECTRUM; i++) {
        float t = float(i) / float(SPECTRUM - 1);
        vec3 weight = clamp(vec3(1.0 - 2.0 * t, 1.0 - abs(2.0 * t - 1.0), 2.0 * t - 1.0), 0.0, 1.0);
        sum += fetch(uv + shift * (2.0 * t - 1.0)) * weight;
        total += weight;
    }
    return sum / total;
}

vec3 torn(vec2 uv, float a, float b) {
    vec2 from = fract(uv + vec2(a - 0.5, (b - 0.5) * 0.4));
    vec2 spread = vec2(0.015 + 0.04 * b, 0.0);
    vec3 rgb = vec3(texture(DiffuseSampler, from + spread).r, texture(DiffuseSampler, from).g, texture(DiffuseSampler, from - spread).b);
    return floor(rgb * 0.55 * 4.0 + 0.5) / 4.0;
}

vec3 glitch(vec2 uv, vec3 colour) {
    float frame = floor(Time * 20.0) + floor(VhsSeed * 997.0);
    float coarse = floor(uv.y * 7.0);
    float band = hash(vec2(coarse, frame)) < 0.5 ? coarse : 16.0 + floor(uv.y * 64.0);
    if (hash(vec2(band, frame + 7.7)) > Vhs * 1.2) return colour;
    float pick = hash(vec2(band, frame + 3.1));
    float a = hash(vec2(band, frame + 11.3));
    float b = hash(vec2(band, frame + 19.9));
    bool shifts = VhsShake > 0.5;
    if (pick < 0.42 && shifts) return torn(uv, a, b);
    if (pick < 0.58 && shifts) return texture(DiffuseSampler, vec2(a, uv.y)).rgb * 0.45;
    if (pick < 0.82) return vec3(0.02) + vec3(0.35) * step(fract(uv.y * 64.0), 0.08) * step(0.7, b);
    if (pick < 0.93) {
        vec3 bar = vec3(step(0.5, a), step(0.5, b), step(0.5, fract(a + b)));
        return mix(vec3(0.12), bar, 0.45);
    }
    vec2 block = floor(gl_FragCoord.xy / vec2(14.0, 4.0));
    return vec3(0.6 * step(0.55, hash(block + frame)));
}

vec3 shoulder(vec3 colour) {
    vec3 over = max(colour - Shoulder, 0.0);
    return min(colour, vec3(Shoulder)) + over / (1.0 + over / (1.0 - Shoulder));
}

void main() {
    vec2 uv = texCoord;
    vec2 centred = uv - 0.5;
    vec2 aspect = vec2(InSize.x / InSize.y, 1.0);
    float radius = length(centred * aspect) / length(0.5 * aspect);

    vec3 colour;
    if (Aberration > 0.0 || Ghost > 0.0) {
        vec2 shift = centred * Aberration * 0.008 * (0.35 + 2.6 * dot(centred, centred));
        colour = split(uv, shift);
        if (Ghost > 0.0) {
            vec3 ghost = split(uv + vec2(0.012, 0.004) * Ghost, shift * 1.5);
            colour = mix(colour, ghost, 0.35 * min(Ghost, 1.0));
        }
    } else {
        colour = fetch(uv);
    }

    float world = Lit * (Hands > 0.5 ? step(1.0, texelFetch(DepthSampler, ivec2(gl_FragCoord.xy), 0).r) : 1.0);
    vec2 light = texture(LightSampler, uv).rg;
    colour = colour * mix(1.0, light.r, world) + HALATION * (light.g * Haze * world);

    colour += texture(BloomSampler, uv).rgb * HALATION * Bloom * Strength;
    colour = mix(colour, shoulder(colour), Strength);

    float luma = dot(colour, LUMA);
    colour = mix(colour, vec3(luma), Night * Strength * (1.0 - smoothstep(0.0005, 0.02, luma)));

    float tunnel = Tunnel * smoothstep(0.15, 0.95, radius);
    colour = mix(colour, vec3(dot(colour, LUMA)), clamp(Drain + tunnel, 0.0, 1.0));
    colour *= 1.0 - clamp(0.85 * tunnel, 0.0, 1.0);
    colour *= 1.0 - Vignette * Strength * smoothstep(0.4, 1.15, radius);

    colour = pow(max(colour, 0.0), vec3(1.0 / 2.2));

    vec2 cell = gl_FragCoord.xy + mod(floor(Time * 24.0), 64.0) * vec2(37.0, 17.0);
    float noise = hash(cell) + hash(cell + 91.7) - 1.0;
    float shade = clamp(dot(colour, LUMA), 0.0, 1.0);
    colour += noise * (Grain * Strength * (1.0 - 0.7 * shade) + 1.0 / 255.0);
    if (Vhs > 0.0) colour = glitch(uv, colour);
    fragColor = vec4(clamp(colour, 0.0, 1.0), 1.0);
}
