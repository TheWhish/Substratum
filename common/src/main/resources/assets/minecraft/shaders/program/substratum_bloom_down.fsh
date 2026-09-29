#version 150

uniform sampler2D DiffuseSampler;
uniform vec2 InSize;

in vec2 texCoord;

out vec4 fragColor;

vec3 tap(float x, float y) {
    return texture(DiffuseSampler, texCoord + vec2(x, y) / InSize).rgb;
}

void main() {
    vec3 corners = tap(-2.0, 2.0) + tap(2.0, 2.0) + tap(-2.0, -2.0) + tap(2.0, -2.0);
    vec3 edges = tap(0.0, 2.0) + tap(-2.0, 0.0) + tap(2.0, 0.0) + tap(0.0, -2.0);
    vec3 inner = tap(-1.0, 1.0) + tap(1.0, 1.0) + tap(-1.0, -1.0) + tap(1.0, -1.0);
    fragColor = vec4(tap(0.0, 0.0) * 0.125 + corners * 0.03125 + edges * 0.0625 + inner * 0.125, 1.0);
}
