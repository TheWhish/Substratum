#version 150

uniform sampler2D DiffuseSampler;
uniform sampler2D BaseSampler;
uniform vec2 InSize;
uniform float Scatter;

in vec2 texCoord;

out vec4 fragColor;

vec3 tap(float x, float y) {
    return texture(DiffuseSampler, texCoord + vec2(x, y) / InSize).rgb;
}

void main() {
    vec3 tent = tap(0.0, 0.0) * 4.0
        + (tap(1.0, 0.0) + tap(-1.0, 0.0) + tap(0.0, 1.0) + tap(0.0, -1.0)) * 2.0
        + tap(1.0, 1.0) + tap(-1.0, 1.0) + tap(1.0, -1.0) + tap(-1.0, -1.0);
    fragColor = vec4(mix(texture(BaseSampler, texCoord).rgb, tent / 16.0, Scatter), 1.0);
}
