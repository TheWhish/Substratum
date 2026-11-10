const float CAPSULE_DEPTH = 0.8;
const float CAPSULE_REACH = 8.0;
const float CAPSULE_SKIN = 0.2;
const float CAPSULE_SOFT = 1.5;
const float CAPSULE_HUG = 0.45;
const float CAPSULE_MIN_PENUMBRA = 0.04;

vec3 capsuleAxis(vec3 point, vec3 bottom, vec3 top) {
    vec3 axis = top - bottom;
    return bottom + axis * clamp(dot(point - bottom, axis) / max(dot(axis, axis), 1e-6), 0.0, 1.0);
}

float capsuleGap(vec3 point, vec3 bottom, vec3 top) {
    return distance(point, capsuleAxis(point, bottom, top));
}

vec2 capsuleClosest(vec3 from, vec3 to, vec3 bottom, vec3 top) {
    vec3 ray = to - from;
    vec3 axis = top - bottom;
    vec3 offset = from - bottom;
    float rr = max(dot(ray, ray), 1e-6);
    float aa = max(dot(axis, axis), 1e-6);
    float ra = dot(ray, axis);
    float ro = dot(ray, offset);
    float ao = dot(axis, offset);
    float denominator = rr * aa - ra * ra;
    float s = denominator > 1e-6 ? clamp((ra * ao - ro * aa) / denominator, 0.0, 1.0) : 0.0;
    float t = (ra * s + ao) / aa;
    if (t < 0.0) {
        t = 0.0;
        s = clamp(-ro / rr, 0.0, 1.0);
    } else if (t > 1.0) {
        t = 1.0;
        s = clamp((ra - ro) / rr, 0.0, 1.0);
    }
    return vec2(s, t);
}

float capsuleShadow(vec3 from, vec3 light, float size, vec3 bottom, vec3 top, float radius) {
    vec2 closest = capsuleClosest(from, light, bottom, top);
    float gap = distance(mix(from, light, closest.x), mix(bottom, top, closest.y));
    float travel = distance(from, light);
    float near = closest.x * travel;
    float penumbra = max(size * near / max(travel, 1e-3), CAPSULE_MIN_PENUMBRA);
    return mix(1.0 - CAPSULE_DEPTH, 1.0, smoothstep(radius - penumbra, radius + penumbra, gap));
}

float capsuleOcclusion(vec3 point, vec3 normal, vec3 bottom, vec3 top, float radius) {
    vec3 toAxis = capsuleAxis(point, bottom, top) - point;
    float d2 = max(dot(toAxis, toAxis), 1e-4);
    float soft = radius * CAPSULE_SOFT;
    return CAPSULE_HUG * clamp(dot(normal, toAxis) * inversesqrt(d2) * soft * soft / d2, 0.0, 1.0);
}
