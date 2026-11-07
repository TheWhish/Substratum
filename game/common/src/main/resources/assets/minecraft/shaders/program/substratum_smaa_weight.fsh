#version 150
uniform vec2 InSize;

#define SMAA_GLSL_3
#define SMAA_PRESET_ULTRA
#define SMAA_RT_METRICS vec4(1.0 / InSize, InSize)
#define SMAA_INCLUDE_VS 0
#moj_import <smaa.glsl>

uniform sampler2D DiffuseSampler;
uniform sampler2D AreaSampler;
uniform sampler2D SearchSampler;

in vec2 texCoord;
in vec2 pixCoord;
in vec4 offset[3];

out vec4 fragColor;

void main() {
    fragColor = SMAABlendingWeightCalculationPS(texCoord, pixCoord, offset, DiffuseSampler, AreaSampler, SearchSampler, vec4(0.0));
}
