#version 150

uniform sampler2D DepthSampler;
uniform sampler3D LightVolume;
uniform usampler2D Tiles;
uniform mat4 InvViewProj;
uniform vec3 Camera;
uniform vec2 InSize;
uniform int TilesX;
uniform float FogStart;
uniform float FogEnd;
uniform float Gain;
uniform float Shade;
uniform int PortalCount;
uniform vec4 Portals[24];

layout(std140) uniform LampBlock {
    vec4 Lamp[256];
};

out vec4 fragColor;

const int TILE = 64;
const int PER_TILE = 96;
const float RANGE = 12.0;
const float SPREAD = 3.0;
const float REACH = 9.0;
const float SPILL = 0.8;
const float LIFT = 0.5;
const float FALLOFF = 0.5;
const float SOFT = 0.01;
const float BULB = 0.35;
const int MARCH = 12;
const float OPEN = 0.03;
const float FAINT = 0.0005;
const float GLOW_FAINT = 0.00003;
const vec3 VOLUME = vec3(256.0, 256.0, 16.0);

float depthAt(ivec2 texel) {
    return texelFetch(DepthSampler, clamp(texel, ivec2(0), ivec2(InSize) - 1), 0).r;
}

vec3 unproject(ivec2 texel, float depth) {
    vec2 uv = (vec2(texel) + 0.5) / InSize;
    vec4 point = InvViewProj * vec4(vec3(uv, depth) * 2.0 - 1.0, 1.0);
    return point.xyz / point.w;
}

vec3 position(ivec2 texel) {
    return unproject(texel, depthAt(texel));
}

bool open(vec3 point) {
    vec3 cell = floor(Camera + point);
    if (cell.y < 0.0 || cell.y >= VOLUME.z) return true;
    return texture(LightVolume, (cell + 0.5).xzy / VOLUME).r > OPEN;
}

bool foreign(vec3 point) {
    for (int i = 0; i < 8; i++) {
        if (i >= PortalCount) break;
        vec3 q = point - Portals[i * 3].xyz;
        vec3 w = Portals[i * 3 + 1].xyz;
        vec3 h = Portals[i * 3 + 2].xyz;
        if (abs(dot(q, normalize(cross(w, h)))) > 0.01 + 0.002 * length(point)) continue;
        if (abs(dot(q, w)) <= dot(w, w) + 0.01 && abs(dot(q, h)) <= dot(h, h) + 0.01) return true;
    }
    return false;
}

float visible(vec3 from, vec3 to) {
    vec3 delta = to - from;
    int steps = min(int(length(delta)), MARCH);
    vec3 hop = delta / float(steps + 1);
    for (int i = 1; i <= MARCH; i++) {
        if (i > steps) break;
        if (!open(from + hop * float(i))) return 0.0;
    }
    return 1.0;
}

vec3 tangent(vec3 centre, vec3 before, vec3 after) {
    vec3 a = centre - before;
    vec3 b = after - centre;
    return dot(a, a) < dot(b, b) ? a : b;
}

float panelGlow(vec3 source, vec3 direction, float from, float to) {
    float t0 = dot(source, direction);
    float h2 = max(dot(source, source) - t0 * t0, 0.0) + SOFT;
    float c = source.y - t0 * direction.y;
    float u0 = from - t0;
    float u1 = to - t0;
    return (c * u1 / h2 + direction.y) * inversesqrt(h2 + u1 * u1) - (c * u0 / h2 + direction.y) * inversesqrt(h2 + u0 * u0);
}

float bulbGlow(vec3 source, vec3 direction, float from, float to) {
    float t0 = dot(source, direction);
    float h = sqrt(max(dot(source, source) - t0 * t0, 0.0) + 0.2);
    return (atan((to - t0) / h) - atan((from - t0) / h)) / h;
}

void main() {
    ivec2 texel = ivec2(gl_FragCoord.xy) * 2;
    float depth = depthAt(texel);
    bool surface = depth < 1.0;
    vec3 point = unproject(texel, depth);
    surface = surface && !foreign(point);
    float distance = length(point);
    vec3 direction = point / max(distance, 1e-4);
    float span = min(distance, FogEnd);

    vec3 normal = vec3(0.0, 1.0, 0.0);
    if (surface) {
        vec3 dx = tangent(point, position(texel - ivec2(2, 0)), position(texel + ivec2(2, 0)));
        vec3 dy = tangent(point, position(texel - ivec2(0, 2)), position(texel + ivec2(0, 2)));
        normal = normalize(cross(dx, dy));
        if (dot(normal, point) > 0.0) normal = -normal;
    }
    vec3 start = point + normal * 0.1;

    float direct = 0.0;
    float full = 0.5 / max(Gain, 1e-4) + Shade;
    float glow = 0.0;
    float emission = 0.0;
    ivec2 cell = ivec2(gl_FragCoord.xy) / TILE;
    int row = cell.y * TilesX + cell.x;
    int count = int(texelFetch(Tiles, ivec2(0, row), 0).r);
    uvec4 group = uvec4(0u);
    for (int i = 0; i < PER_TILE; i++) {
        if (i >= count) break;
        if ((i & 3) == 0) group = texelFetch(Tiles, ivec2(1 + (i >> 2), row), 0);
        vec4 lamp = Lamp[int(group[i & 3])];
        vec3 toLamp = lamp.xyz - point;
        float d2 = dot(toLamp, toLamp);
        float t0 = dot(lamp.xyz, direction);
        bool near = surface && d2 < RANGE * RANGE;
        bool crossed = dot(lamp.xyz, lamp.xyz) - t0 * t0 < REACH * REACH && t0 > -REACH && t0 < span + REACH;
        if (!near && !crossed) continue;
        bool panel = lamp.w > 0.0;
        float strength = abs(lamp.w);
        vec3 face = lamp.xyz - vec3(0.0, panel ? 0.3 : 0.0, 0.0);

        if (near) {
            if (panel && abs(toLamp.y) < 0.05 && max(abs(toLamp.x), abs(toLamp.z)) < 0.5) {
                emission = max(emission, min(strength, 1.0));
            } else {
                vec3 l = normalize(toLamp + vec3(0.0, panel ? SPILL : 0.0, 0.0));
                float receive = dot(normal, l);
                float emit = panel ? l.y : 1.0;
                if (receive > 0.0 && emit > 0.0) {
                    float fade = 1.0 - smoothstep(RANGE * 0.5, RANGE, sqrt(d2));
                    float amount = strength * sqrt(emit) * receive * fade / (d2 + SPREAD * SPREAD);
                    if (amount > FAINT && direct < full) direct += amount * visible(start, face);
                }
            }
        }

        if (crossed) {
            float from = 0.0;
            float to = span;
            if (panel) {
                if (direction.y > 0.0) to = min(to, lamp.y / direction.y);
                else if (direction.y < 0.0) from = max(from, lamp.y / direction.y);
                else if (lamp.y <= 0.0) to = from;
            }
            if (to > from) {
                vec3 source = lamp.xyz + vec3(0.0, panel ? LIFT : 0.0, 0.0);
                float amount = panel ? panelGlow(source, direction, from, to) : BULB * bulbGlow(source, direction, from, to);
                float nearest = clamp(dot(source, direction), from, to);
                float gap = max(length(direction * nearest - source) - LIFT, 0.0);
                float edge = min(length(direction * nearest - lamp.xyz) / REACH, 1.0);
                edge *= edge;
                edge = 1.0 - edge * edge;
                amount *= strength * exp(-FALLOFF * gap) * (1.0 - smoothstep(FogStart, FogEnd, nearest)) * edge * edge * edge;
                if (amount > GLOW_FAINT) {
                    glow += amount * visible(direction * max(nearest - 0.15, 0.0), face);
                }
            }
        }
    }

    float fog = surface ? smoothstep(FogStart, FogEnd, distance) : 1.0;
    float factor = emission > 0.0 ? 1.0 : clamp(1.0 + Gain * (direct - Shade * smoothstep(0.0, 0.02, direct)), 0.75, 1.5);
    fragColor = vec4(mix(factor, 1.0, fog), glow, emission * (1.0 - fog), 1.0);
}
