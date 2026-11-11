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
uniform int BeamCount;
uniform vec4 Beams[8];
uniform vec3 BeamCone;
uniform vec2 BeamReach;
uniform int CapsuleCount;
uniform vec4 Capsules[16];

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
const float OCCLUSION = 0.45;
const float NEAR_RING = 0.6;
const float FAR_RING = 0.9;
const float PANEL_HALF = 0.35;
const int BEAMS = 4;
const int BEAM_STEPS = 12;
const int BEAM_MARCH = 104;
const float BEAM_HAZE = 0.015;
const float BEAM_FORWARD = 0.2;
const float BEAM_FAINT = 0.001;
const int CAPSULES = 8;
const float PANEL_SIZE = 0.7;
const float BULB_SIZE = 0.2;

#moj_import <capsule.glsl>

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

float openness(vec3 point) {
    vec3 cell = Camera + point;
    if (cell.y < 0.0 || cell.y >= VOLUME.z) return 1.0;
    return texture(LightVolume, cell.xzy / VOLUME).r;
}

float occlusion(vec3 point, vec3 normal) {
    vec3 u = normalize(cross(normal, abs(normal.y) > 0.5 ? vec3(1.0, 0.0, 0.0) : vec3(0.0, 1.0, 0.0)));
    vec3 v = cross(normal, u);
    vec3 centre = point + normal * 0.5;
    float open = openness(centre + u * NEAR_RING) + openness(centre - u * NEAR_RING)
        + openness(centre + v * NEAR_RING) + openness(centre - v * NEAR_RING)
        + openness(centre + (u + v) * FAR_RING) + openness(centre + (u - v) * FAR_RING)
        + openness(centre - (u - v) * FAR_RING) + openness(centre - (u + v) * FAR_RING);
    return 1.0 - OCCLUSION * (1.0 - open / 8.0);
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

bool solid(ivec3 cell) {
    if (cell.y < 0 || cell.y >= int(VOLUME.z)) return false;
    return texelFetch(LightVolume, ivec3(cell.x & 255, cell.z & 255, cell.y), 0).r <= 0.5;
}

float clear(vec3 from, vec3 to) {
    vec3 start = Camera + from;
    vec3 delta = to - from;
    ivec3 cell = ivec3(floor(start));
    ivec3 last = ivec3(floor(Camera + to));
    ivec3 stride = ivec3(sign(delta));
    vec3 span = 1.0 / max(abs(delta), vec3(1e-6));
    vec3 next = mix(start - floor(start), floor(start) + 1.0 - start, greaterThan(delta, vec3(0.0))) * span;
    for (int i = 0; i < BEAM_MARCH; i++) {
        if (cell == last) return 1.0;
        if (next.x < next.y && next.x < next.z) {
            cell.x += stride.x;
            next.x += span.x;
        } else if (next.y < next.z) {
            cell.y += stride.y;
            next.y += span.y;
        } else {
            cell.z += stride.z;
            next.z += span.z;
        }
        if (cell != last && solid(cell)) return 0.0;
    }
    return 1.0;
}

float beamHaze(vec3 direction, float span) {
    float jitter = fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715))));
    float range = BeamReach.y;
    float total = 0.0;
    for (int i = 0; i < BEAMS; i++) {
        if (i >= BeamCount) break;
        vec4 source = Beams[i * 2];
        vec4 aim = Beams[i * 2 + 1];
        float t0 = dot(source.xyz, direction);
        float h2 = dot(source.xyz, source.xyz) - t0 * t0;
        if (h2 >= range * range) continue;
        float chord = sqrt(range * range - h2);
        float from = max(t0 - chord, 0.0);
        float to = min(t0 + chord, span);
        if (to <= from) continue;
        float hop = (to - from) / float(BEAM_STEPS);
        float sum = 0.0;
        for (int s = 0; s < BEAM_STEPS; s++) {
            vec3 point = direction * (from + (float(s) + jitter) * hop);
            vec3 ray = point - source.xyz;
            float d2 = dot(ray, ray);
            if (d2 < 1e-4) continue;
            vec3 travel = ray * inversesqrt(d2);
            float cone = smoothstep(BeamCone.y, BeamCone.x, dot(travel, aim.xyz));
            if (cone <= 0.0) continue;
            float g = -dot(travel, direction);
            float phase = (1.0 - BEAM_FORWARD * BEAM_FORWARD) / pow(1.0 + BEAM_FORWARD * BEAM_FORWARD - 2.0 * BEAM_FORWARD * g, 1.5);
            float amount = cone * cone * phase / (1.0 + d2 / (BeamCone.z * BeamCone.z)) * (1.0 - smoothstep(BeamReach.x, range, sqrt(d2)));
            if (amount > BEAM_FAINT) amount *= clear(source.xyz, point);
            sum += amount;
        }
        total += source.w * aim.w * sum * hop;
    }
    return total * BEAM_HAZE;
}

float occluded(vec3 from, vec3 light, float size, int mask) {
    if (mask == 0) return 1.0;
    float lit = 1.0;
    for (int i = 0; i < CAPSULES; i++) {
        if ((mask & (1 << i)) == 0) continue;
        vec4 bottom = Capsules[i * 2];
        lit *= capsuleShadow(from, light, size, bottom.xyz, Capsules[i * 2 + 1].xyz, bottom.w);
    }
    return lit;
}

float shadow(vec3 from, vec3 face, bool panel) {
    if (!panel) return visible(from, face);
    float turn = (int(gl_FragCoord.x + gl_FragCoord.y) & 1) == 0 ? PANEL_HALF : -PANEL_HALF;
    vec3 offset = vec3(PANEL_HALF, 0.0, turn);
    return 0.5 * (visible(from, face + offset) + visible(from, face - offset));
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

    int occluders = 0;
    float hug = 0.0;
    if (surface) {
        for (int i = 0; i < CAPSULES; i++) {
            if (i >= CapsuleCount) break;
            vec4 bottom = Capsules[i * 2];
            vec3 top = Capsules[i * 2 + 1].xyz;
            float gap = capsuleGap(point, bottom.xyz, top);
            if (gap >= CAPSULE_REACH) continue;
            hug += capsuleOcclusion(point, normal, bottom.xyz, top, bottom.w);
            if (gap > bottom.w + CAPSULE_SKIN) occluders |= 1 << i;
        }
    }

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
                    if (amount > FAINT && direct < full) direct += amount * shadow(start, face, panel) * occluded(start, face, panel ? PANEL_SIZE : BULB_SIZE, occluders);
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

    glow += beamHaze(direction, span);
    float fog = surface ? smoothstep(FogStart, FogEnd, distance) : 1.0;
    float factor = emission > 0.0 ? 1.0 : clamp(1.0 + Gain * (direct - Shade * smoothstep(0.0, 0.02, direct)), 0.75, 1.5);
    if (surface && emission <= 0.0) factor *= occlusion(point, normal) * (1.0 - min(hug, CAPSULE_HUG));
    fragColor = vec4(mix(factor, 1.0, fog), glow, emission * (1.0 - fog), 1.0);
}
