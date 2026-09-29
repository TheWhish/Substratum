#version 150

uniform sampler2D DiffuseSampler;
uniform vec2 InSize;

in vec2 texCoord;

out vec4 fragColor;

const float EDGE_MIN = 0.0312;
const float EDGE_MAX = 0.166;
const float SUBPIXEL = 0.5;
const int STEPS = 10;
const float QUALITY[STEPS] = float[](1.0, 1.0, 1.0, 1.0, 1.0, 1.5, 2.0, 2.0, 4.0, 8.0);

float luma(vec2 uv) {
    return dot(texture(DiffuseSampler, uv).rgb, vec3(0.299, 0.587, 0.114));
}

float lumaAt(ivec2 offset) {
    return dot(texelFetch(DiffuseSampler, clamp(ivec2(gl_FragCoord.xy) + offset, ivec2(0), ivec2(InSize) - 1), 0).rgb, vec3(0.299, 0.587, 0.114));
}

void main() {
    vec3 centre = texelFetch(DiffuseSampler, ivec2(gl_FragCoord.xy), 0).rgb;
    float lumaCentre = dot(centre, vec3(0.299, 0.587, 0.114));
    float lumaDown = lumaAt(ivec2(0, -1));
    float lumaUp = lumaAt(ivec2(0, 1));
    float lumaLeft = lumaAt(ivec2(-1, 0));
    float lumaRight = lumaAt(ivec2(1, 0));
    float lumaMin = min(lumaCentre, min(min(lumaDown, lumaUp), min(lumaLeft, lumaRight)));
    float lumaMax = max(lumaCentre, max(max(lumaDown, lumaUp), max(lumaLeft, lumaRight)));
    float range = lumaMax - lumaMin;
    if (range < max(EDGE_MIN, lumaMax * EDGE_MAX)) {
        fragColor = vec4(centre, 1.0);
        return;
    }

    float lumaDownLeft = lumaAt(ivec2(-1, -1));
    float lumaUpRight = lumaAt(ivec2(1, 1));
    float lumaUpLeft = lumaAt(ivec2(-1, 1));
    float lumaDownRight = lumaAt(ivec2(1, -1));
    float downUp = lumaDown + lumaUp;
    float leftRight = lumaLeft + lumaRight;
    float leftCorners = lumaDownLeft + lumaUpLeft;
    float downCorners = lumaDownLeft + lumaDownRight;
    float rightCorners = lumaDownRight + lumaUpRight;
    float upCorners = lumaUpRight + lumaUpLeft;
    float edgeHorizontal = abs(-2.0 * lumaLeft + leftCorners) + abs(-2.0 * lumaCentre + downUp) * 2.0 + abs(-2.0 * lumaRight + rightCorners);
    float edgeVertical = abs(-2.0 * lumaUp + upCorners) + abs(-2.0 * lumaCentre + leftRight) * 2.0 + abs(-2.0 * lumaDown + downCorners);
    bool horizontal = edgeHorizontal >= edgeVertical;

    vec2 texel = 1.0 / InSize;
    vec2 uv = (floor(gl_FragCoord.xy) + 0.5) * texel;
    float luma1 = horizontal ? lumaDown : lumaLeft;
    float luma2 = horizontal ? lumaUp : lumaRight;
    float gradient1 = luma1 - lumaCentre;
    float gradient2 = luma2 - lumaCentre;
    bool steepest1 = abs(gradient1) >= abs(gradient2);
    float gradientScaled = 0.25 * max(abs(gradient1), abs(gradient2));
    float stepLength = horizontal ? texel.y : texel.x;
    float localAverage = 0.5 * ((steepest1 ? luma1 : luma2) + lumaCentre);
    if (steepest1) stepLength = -stepLength;

    vec2 current = uv + (horizontal ? vec2(0.0, stepLength * 0.5) : vec2(stepLength * 0.5, 0.0));
    vec2 offset = horizontal ? vec2(texel.x, 0.0) : vec2(0.0, texel.y);
    vec2 uv1 = current - offset;
    vec2 uv2 = current + offset;
    float end1 = luma(uv1) - localAverage;
    float end2 = luma(uv2) - localAverage;
    bool reached1 = abs(end1) >= gradientScaled;
    bool reached2 = abs(end2) >= gradientScaled;
    if (!reached1) uv1 -= offset;
    if (!reached2) uv2 += offset;
    for (int i = 2; i < STEPS; i++) {
        if (reached1 && reached2) break;
        if (!reached1) end1 = luma(uv1) - localAverage;
        if (!reached2) end2 = luma(uv2) - localAverage;
        reached1 = abs(end1) >= gradientScaled;
        reached2 = abs(end2) >= gradientScaled;
        if (!reached1) uv1 -= offset * QUALITY[i];
        if (!reached2) uv2 += offset * QUALITY[i];
    }

    float distance1 = horizontal ? uv.x - uv1.x : uv.y - uv1.y;
    float distance2 = horizontal ? uv2.x - uv.x : uv2.y - uv.y;
    bool towards1 = distance1 < distance2;
    float edgeOffset = 0.5 - min(distance1, distance2) / (distance1 + distance2);
    bool centreDarker = lumaCentre < localAverage;
    bool correct = ((towards1 ? end1 : end2) < 0.0) != centreDarker;
    float average = (2.0 * (downUp + leftRight) + leftCorners + rightCorners) / 12.0;
    float subpixel = clamp(abs(average - lumaCentre) / range, 0.0, 1.0);
    subpixel = (-2.0 * subpixel + 3.0) * subpixel * subpixel;
    float shift = max(correct ? edgeOffset : 0.0, subpixel * subpixel * SUBPIXEL);
    vec2 result = uv + (horizontal ? vec2(0.0, shift * stepLength) : vec2(shift * stepLength, 0.0));
    fragColor = vec4(texture(DiffuseSampler, result).rgb, 1.0);
}
