#version 150
uniform vec2 InSize;

#define SMAA_GLSL_3
#define SMAA_PRESET_ULTRA
#define SMAA_RT_METRICS vec4(1.0 / InSize, InSize)
#define SMAA_INCLUDE_PS 0
#moj_import <smaa.glsl>

out vec2 texCoord;
out vec4 offset;

void main() {
    texCoord = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
    gl_Position = vec4(texCoord * 2.0 - 1.0, 0.0, 1.0);
    SMAANeighborhoodBlendingVS(texCoord, offset);
}
