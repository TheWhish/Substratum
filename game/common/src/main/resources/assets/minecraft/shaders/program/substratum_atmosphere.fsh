#version 150

uniform sampler2D DiffuseSampler;
uniform sampler2D BloomSampler;
uniform sampler2D LightSampler;
uniform sampler2D DepthSampler;
uniform float Strength;
uniform float Bloom;
uniform float Shoulder;
uniform float Night;
uniform float Lit;
uniform float Hands;
uniform float Haze;

in vec2 texCoord;

out vec4 fragColor;

const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);
const vec3 HALATION = vec3(1.0, 0.93, 0.8);

vec3 shoulder(vec3 colour) {
    vec3 over = max(colour - Shoulder, 0.0);
    return min(colour, vec3(Shoulder)) + over / (1.0 + over / (1.0 - Shoulder));
}

void main() {
    vec3 colour = pow(texture(DiffuseSampler, texCoord).rgb, vec3(2.2));

    float world = Lit * (Hands > 0.5 ? step(1.0, texelFetch(DepthSampler, ivec2(gl_FragCoord.xy), 0).r) : 1.0);
    vec2 light = texture(LightSampler, texCoord).rg;
    colour = colour * mix(1.0, light.r, world) + HALATION * (light.g * Haze * world);

    colour += texture(BloomSampler, texCoord).rgb * HALATION * Bloom * Strength;
    colour = mix(colour, shoulder(colour), Strength);

    float luma = dot(colour, LUMA);
    colour = mix(colour, vec3(luma), Night * Strength * (1.0 - smoothstep(0.0005, 0.02, luma)));

    fragColor = vec4(pow(clamp(colour, 0.0, 1.0), vec3(1.0 / 2.2)), 1.0);
}
