#version 150

uniform sampler2D DiffuseSampler;
uniform sampler2D LightSampler;
uniform sampler2D DepthSampler;
uniform sampler2D MaskSampler;
uniform vec2 InSize;
uniform float Threshold;
uniform float Knee;
uniform float Lit;
uniform float Hands;
uniform float Haze;
uniform float Emission;
uniform float Masked;

in vec2 texCoord;

out vec4 fragColor;

const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);
const vec3 WARM = vec3(1.0, 0.93, 0.8);
const float LIMIT = 3.0;

void main() {
    vec3 sum = vec3(0.0);
    float total = 0.0;
    float coverage = 0.0;
    for (int i = 0; i < 4; i++) {
        vec2 uv = texCoord + (vec2(float(i & 1), float(i >> 1)) * 2.0 - 1.0) / InSize;
        float inside = mix(1.0, texture(MaskSampler, uv).r, Masked);
        vec3 colour = pow(texture(DiffuseSampler, uv).rgb, vec3(2.2));
        float weight = inside / (1.0 + dot(colour, LUMA));
        sum += colour * weight;
        total += weight;
        coverage += inside;
    }
    float world = Lit * (Hands > 0.5 ? step(1.0, texelFetch(DepthSampler, ivec2(gl_FragCoord.xy) * 2, 0).r) : 1.0);
    vec3 light = texture(LightSampler, texCoord).rgb;
    vec3 base = total > 0.0 ? sum / total : vec3(0.0);
    vec3 colour = (base * mix(1.0, light.r, world) + WARM * (light.g * Haze * world)) * (1.0 + Emission * light.b * world) * (coverage * 0.25);
    colour *= LIMIT / max(LIMIT, dot(colour, LUMA));
    float luma = dot(colour, LUMA);
    float soft = clamp(luma - Threshold + Knee, 0.0, 2.0 * Knee);
    soft = soft * soft / (4.0 * Knee + 1e-5);
    fragColor = vec4(colour * max(soft, luma - Threshold) / max(luma, 1e-5), 1.0);
}
