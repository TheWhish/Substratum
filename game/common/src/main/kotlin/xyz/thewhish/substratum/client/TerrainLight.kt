package xyz.thewhish.substratum.client

import net.minecraft.client.Camera
import net.minecraft.client.Minecraft
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.level.ChunkPos
import org.lwjgl.opengl.GL15
import org.lwjgl.opengl.GL30
import org.lwjgl.opengl.GL31
import org.lwjgl.system.MemoryUtil
import qouteall.imm_ptl.core.render.context_management.PortalRendering
import xyz.thewhish.substratum.Substratum
import xyz.thewhish.substratum.level.SubstratumLevels
import java.util.Locale
import kotlin.math.floor

object TerrainLight {

    private const val BLOCK = 7
    private const val BYTES = 48 + (Flashlights.MAX + Capsules.MAX) * 32
    private const val SHADER = "blocks/block_layer_opaque"

    private class Edit(val anchor: String, val replacement: String)

    private val vertexEdits = listOf(
        Edit("out vec4 v_Color;", "out vec4 v_Color;\nout vec4 v_Light;\nout vec3 v_Position;"),
        Edit(
            "v_Color = _vert_color * texture(u_LightTex, _vert_tex_light_coord);",
            "v_Light = texture(u_LightTex, _vert_tex_light_coord);\n    v_Color = _vert_color;\n    v_Position = position;"
        )
    )

    private val fragmentEdits = listOf(
        Edit("#version 330 core", "#version 330 core\n#extension GL_ARB_shading_language_420pack : enable"),
        Edit("in vec4 v_Color;", "in vec4 v_Color;\nin vec4 v_Light;\nin vec3 v_Position;"),
        Edit("void main() {", library() + "\nvoid main() {"),
        Edit("color *= v_Color;", "color *= substratumLight(v_Position, v_Color, v_Light);")
    )

    private val shaders: Pair<String, String>? by lazy(::load)
    private val data = MemoryUtil.memCalloc(BYTES)
    private val outer = ArrayDeque<ByteArray>()
    private var buffer = -1

    @Volatile
    private var served = false

    @JvmStatic
    fun patch(name: ResourceLocation, source: String): String {
        if (name.namespace != "sodium") return source
        val patched = shaders ?: return source
        return when (name.path) {
            "$SHADER.vsh" -> patched.first.also { served = true }
            "$SHADER.fsh" -> patched.second
            else -> source
        }
    }

    fun rewrite(vertex: String, fragment: String): Pair<String, String>? {
        val vsh = edit(vertex, vertexEdits) ?: return null
        val fsh = edit(fragment, fragmentEdits) ?: return null
        return vsh to fsh
    }

    @JvmStatic
    fun beforeWorld(minecraft: Minecraft, camera: Camera, partialTick: Float) {
        val level = minecraft.level?.takeIf { it.dimension() == SubstratumLevels.LEVEL_0 }
        if (PortalRendering.isRendering()) {
            Probe.portalView()
        } else {
            Flashlights.update(minecraft, partialTick)
            Capsules.update(minecraft, partialTick)
            if (level != null) LightVolume.update(level, ChunkPos(camera.blockPosition), SubstratumLevels.SIGHT_CHUNKS)
        }
        if (!served) return
        outer.addLast(ByteArray(BYTES).also { data.get(0, it) })
        write(minecraft, camera, partialTick, level != null && LightVolume.covers(level))
        upload()
        LightVolume.bindTerrain()
    }

    @JvmStatic
    fun afterWorld() {
        val previous = outer.removeLastOrNull() ?: return
        data.clear()
        data.put(previous).flip()
        upload()
    }

    private fun write(minecraft: Minecraft, camera: Camera, partialTick: Float, enabled: Boolean) {
        val position = camera.position
        val chunkX = floor(position.x / 16).toInt()
        val chunkZ = floor(position.z / 16).toInt()
        data.clear()
        data.putInt(chunkX).putInt(chunkZ).putInt(if (enabled) 1 else 0).putInt(if (enabled) Flashlights.count else 0)
        data.putFloat((position.x - chunkX * 16.0).toFloat()).putFloat(position.y.toFloat()).putFloat((position.z - chunkZ * 16.0).toFloat()).putFloat(if (enabled) Capsules.count.toFloat() else 0f)
        data.putFloat(BackroomsLight.floor()).putFloat(BackroomsLight.vision(minecraft, partialTick)).putFloat(0f).putFloat(0f)
        for (beam in Flashlights.beams) {
            data.putFloat((beam.x - chunkX * 16.0).toFloat()).putFloat(beam.y.toFloat()).putFloat((beam.z - chunkZ * 16.0).toFloat()).putFloat(beam.power)
            data.putFloat(beam.direction.x).putFloat(beam.direction.y).putFloat(beam.direction.z).putFloat(0f)
        }
        Capsules.write(chunkX * 16.0, 0.0, chunkZ * 16.0) { data.putFloat(it) }
        data.flip()
    }

    private fun upload() {
        if (buffer < 0) buffer = GL15.glGenBuffers()
        GL15.glBindBuffer(GL31.GL_UNIFORM_BUFFER, buffer)
        GL15.glBufferData(GL31.GL_UNIFORM_BUFFER, data, GL15.GL_STREAM_DRAW)
        GL30.glBindBufferBase(GL31.GL_UNIFORM_BUFFER, BLOCK, buffer)
        GL15.glBindBuffer(GL31.GL_UNIFORM_BUFFER, 0)
    }

    private fun load(): Pair<String, String>? {
        val vertex = read("assets/sodium/shaders/$SHADER.vsh")
        val fragment = read("assets/sodium/shaders/$SHADER.fsh")
        val patched = if (vertex != null && fragment != null) rewrite(vertex, fragment) else null
        if (patched == null) Substratum.LOGGER.warn("Sodium terrain shaders do not match the expected layout, Level 0 terrain keeps lightmap lighting")
        return patched
    }

    private fun read(path: String): String? =
        TerrainLight::class.java.classLoader.getResourceAsStream(path)?.use { String(it.readAllBytes(), Charsets.UTF_8) }

    private fun edit(source: String, edits: List<Edit>): String? {
        var text = source
        for (edit in edits) {
            val at = text.indexOf(edit.anchor)
            if (at < 0 || text.indexOf(edit.anchor, at + 1) >= 0) return null
            text = text.replaceRange(at, at + edit.anchor.length, edit.replacement)
        }
        return text
    }

    private fun library(): String {
        val capsule = read("assets/substratum/shaders/include/capsule.glsl") ?: error("capsule.glsl is missing from the mod resources")
        fun f(value: Float) = String.format(Locale.ROOT, "%.6f", value)
        fun v(rgb: FloatArray) = "vec3(${f(rgb[0])}, ${f(rgb[1])}, ${f(rgb[2])})"
        return """
#ifdef GL_ARB_shading_language_420pack
layout(std140, binding = $BLOCK) uniform SubstratumTerrain {
    ivec4 st_Origin;
    vec4 st_Camera;
    vec4 st_Curve;
    vec4 st_Beams[${Flashlights.MAX * 2}];
    vec4 st_Capsules[${Capsules.MAX * 2}];
};

layout(binding = ${LightVolume.FIELD_UNIT}) uniform sampler2D st_Field;
layout(binding = ${LightVolume.OWNER_UNIT}) uniform isampler2D st_Owners;
layout(binding = ${LightVolume.UNIT}) uniform sampler3D st_Volume;

const float ST_PROBE = ${f(LightField.PROBE_Y.toFloat())};
const float ST_FADE = ${f(LightField.SHAFT_FADE)};
const float ST_EXPOSURE = ${f(Irradiance.EXPOSURE)};
const float ST_NIGHT = ${f(BackroomsLight.NIGHT_VISION_FLOOR)};
const float ST_TINT_FROM = ${f(BackroomsLight.TINT_FROM)};
const float ST_TINT_TO = ${f(BackroomsLight.TINT_TO)};
const vec3 ST_DIM = ${v(BackroomsLight.DIM)};
const vec3 ST_WARM = ${v(BackroomsLight.WARM)};
const float ST_FULL_BRIGHT = ${f((1f + BackroomsLight.WARM.min()) / 2f)};
const float ST_BASE = ${f(LightVolume.BASE_Y.toFloat())};
const int ST_HEIGHT = ${LightVolume.HEIGHT};
const int ST_SIDE = ${LightVolume.SIDE};
const int ST_MARCH = ${(Flashlights.RANGE * 2).toInt()};
const float ST_COS_INNER = ${f(Flashlights.COS_INNER)};
const float ST_COS_OUTER = ${f(Flashlights.COS_OUTER)};
const float ST_BEAM_NEAR = ${f(Flashlights.NEAR)};
const float ST_BEAM_FADE = ${f(Flashlights.FADE_FROM)};
const float ST_BEAM_RANGE = ${f(Flashlights.RANGE)};
const float ST_BEAM_GAIN = ${f(Flashlights.GAIN)};
const vec3 ST_BEAM_COLOR = ${v(Flashlights.COLOR)};
const float ST_BEAM_FAINT = 0.002;
const float ST_LENS_SIZE = 0.08;

$capsule

bool stOpen(ivec3 cell) {
    if (cell.y < 0 || cell.y >= ST_HEIGHT) return true;
    return texelFetch(st_Volume, ivec3(cell.x & (ST_SIDE - 1), cell.z & (ST_SIDE - 1), cell.y), 0).r > 0.5;
}

vec3 stVolume(vec3 point) {
    ivec2 origin = (st_Origin.xy * 16) & (ST_SIDE - 1);
    return vec3(float(origin.x) + point.x, point.y - ST_BASE, float(origin.y) + point.z);
}

float stVisible(vec3 from, vec3 to) {
    vec3 delta = to - from;
    ivec3 cell = ivec3(floor(from));
    ivec3 last = ivec3(floor(to));
    ivec3 stride = ivec3(sign(delta));
    vec3 span = 1.0 / max(abs(delta), vec3(1e-6));
    vec3 next = mix(from - floor(from), floor(from) + 1.0 - from, greaterThan(delta, vec3(0.0))) * span;
    for (int i = 0; i < ST_MARCH; i++) {
        if (cell == last) return 1.0;
        if (next.x < next.y && next.x < next.z) {
            cell.x += stride.x;
            next.x += span.x;
        } else if (next.y < next.z) {
            cell.y += stride.y;
            next.y += span.y;
        } else {
            cell.z += stride.z;
            next.z += span.z;
        }
        if (cell != last && !stOpen(cell)) return 0.0;
    }
    return 1.0;
}

float stOccluded(vec3 point, vec3 light) {
    float lit = 1.0;
    for (int i = 0; i < ${Capsules.MAX}; i++) {
        if (float(i) >= st_Camera.w) break;
        vec4 bottom = st_Capsules[i * 2];
        vec3 top = st_Capsules[i * 2 + 1].xyz;
        float gap = capsuleGap(point, bottom.xyz, top);
        if (gap >= CAPSULE_REACH || gap <= bottom.w + CAPSULE_SKIN) continue;
        lit *= capsuleShadow(point, light, ST_LENS_SIZE, bottom.xyz, top, bottom.w);
    }
    return lit;
}

vec3 stBeams(vec3 point, vec3 normal) {
    float sum = 0.0;
    for (int i = 0; i < ${Flashlights.MAX}; i++) {
        if (i >= st_Origin.w) break;
        vec4 source = st_Beams[i * 2];
        vec4 aim = st_Beams[i * 2 + 1];
        vec3 toLight = source.xyz - point;
        float d2 = dot(toLight, toLight);
        if (d2 >= ST_BEAM_RANGE * ST_BEAM_RANGE || d2 < 1e-6) continue;
        float d = sqrt(d2);
        vec3 l = toLight / d;
        float facing = dot(normal, l);
        float cone = smoothstep(ST_COS_OUTER, ST_COS_INNER, -dot(l, aim.xyz));
        if (facing <= 0.0 || cone <= 0.0) continue;
        float amount = source.w * ST_BEAM_GAIN * cone * cone * facing / (1.0 + d2 / (ST_BEAM_NEAR * ST_BEAM_NEAR)) * (1.0 - smoothstep(ST_BEAM_FADE, ST_BEAM_RANGE, d));
        if (amount < ST_BEAM_FAINT) continue;
        vec3 lit = point + normal * 0.05;
        amount *= stVisible(stVolume(source.xyz), stVolume(lit)) * stOccluded(lit, source.xyz);
        sum += amount;
    }
    return sum * ST_BEAM_COLOR;
}

vec3 stField(vec3 point, vec3 normal, vec3 fallback) {
    if (st_Origin.z == 0 || min(fallback.r, min(fallback.g, fallback.b)) > ST_FULL_BRIGHT) return fallback;
    vec3 probe = point + normal * 0.5;
    vec2 grid = probe.xz - 0.5;
    vec2 base = floor(grid);
    vec2 f = grid - base;
    ivec2 corner = st_Origin.xy * 16 + ivec2(base);
    float sum = 0.0;
    float weight = 0.0;
    for (int i = 0; i < 4; i++) {
        ivec2 offset = ivec2(i & 1, i >> 1);
        ivec2 cell = corner + offset;
        ivec2 chunk = cell >> 4;
        if (texelFetch(st_Owners, chunk & 15, 0).xy != chunk) continue;
        float energy = texelFetch(st_Field, cell & 255, 0).r;
        if (energy < 0.0) continue;
        vec2 w = mix(1.0 - f, f, vec2(offset));
        sum += w.x * w.y * energy;
        weight += w.x * w.y;
    }
    if (weight < 1e-5) return fallback;
    float energy = sum / weight * pow(ST_FADE, max(ST_PROBE - probe.y, 0.0));
    float pool = 1.0 - exp(-ST_EXPOSURE * max(energy, 0.0));
    float seen = st_Curve.x + (1.0 - st_Curve.x) * pool;
    float lamp = mix(seen, max(seen, ST_NIGHT + (1.0 - ST_NIGHT) * pool), st_Curve.y);
    return clamp(lamp * mix(ST_DIM, ST_WARM, smoothstep(ST_TINT_FROM, ST_TINT_TO, lamp)), 0.0, 1.0);
}

vec4 substratumLight(vec3 position, vec4 color, vec4 fallback) {
    vec3 normal = cross(dFdx(position), dFdy(position));
    float area = dot(normal, normal);
    normal = area > 0.0 ? normal * inversesqrt(area) : vec3(0.0);
    if (dot(normal, position) > 0.0) normal = -normal;
    vec3 point = st_Camera.xyz + position;
    vec3 light = stField(point, normal, fallback.rgb) + stBeams(point, normal);
    return vec4(color.rgb * min(light, vec3(1.0)), color.a * fallback.a);
}
#else
vec4 substratumLight(vec3 position, vec4 color, vec4 fallback) {
    return color * fallback;
}
#endif
"""
    }
}
