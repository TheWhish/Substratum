#version 150
uniform vec2 InSize;

#define SMAA_GLSL_3
#define SMAA_PRESET_ULTRA
#define SMAA_RT_METRICS vec4(1.0 / InSize, InSize)
#define SMAA_INCLUDE_VS 0
#moj_import <smaa.glsl>

uniform sampler2D DiffuseSampler;
uniform sampler2D BlendSampler;

in vec2 texCoord;
in vec4 offset;

out vec4 fragColor;

void main() {
    fragColor = vec4(SMAANeighborhoodBlendingPS(texCoord, offset, DiffuseSampler, BlendSampler).rgb, 1.0);
}
