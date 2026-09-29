#version 150

uniform sampler2D DiffuseSampler;
uniform sampler2D LightSampler;
uniform float Haze;
uniform float Strength;
uniform float Shoulder;

in vec2 texCoord;

out vec4 fragColor;

const vec3 HALATION = vec3(1.0, 0.93, 0.8);

vec3 shoulder(vec3 colour) {
    vec3 over = max(colour - Shoulder, 0.0);
    return min(colour, vec3(Shoulder)) + over / (1.0 + over / (1.0 - Shoulder));
}

void main() {
    vec3 colour = pow(texture(DiffuseSampler, texCoord).rgb, vec3(2.2));
    vec2 light = texture(LightSampler, texCoord).rg;
    colour = colour * light.r + HALATION * (light.g * Haze);
    colour = mix(colour, shoulder(colour), Strength);
    fragColor = vec4(pow(max(colour, 0.0), vec3(1.0 / 2.2)), 1.0);
}
