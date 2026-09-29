package xyz.thewhish.substratum.client

import com.mojang.blaze3d.platform.GlStateManager
import it.unimi.dsi.fastutil.longs.LongOpenHashSet
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.core.SectionPos
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.LightLayer
import net.minecraft.world.phys.Vec3
import org.joml.FrustumIntersection
import org.joml.Matrix4f
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL12
import org.lwjgl.opengl.GL13
import org.lwjgl.opengl.GL15
import org.lwjgl.opengl.GL30
import org.lwjgl.opengl.GL31
import org.lwjgl.system.MemoryUtil
import xyz.thewhish.substratum.level.SubstratumLevels
import xyz.thewhish.substratum.registry.ModBlocks
import xyz.thewhish.substratum.worldgen.MazeChunkGenerator
import java.util.function.LongPredicate
import kotlin.math.sqrt

object LightVolume {

    const val SIDE = 256
    const val HEIGHT = 16
    const val BASE_Y = MazeChunkGenerator.FLOOR_Y
    const val UNIT = 11
    private const val TILE_UNIT = 9
    private const val TILE = 64

    private const val LAMPS = 256
    private const val LAMP_BLOCK = 5
    private const val PER_TILE = 96
    private const val ROW = 4 + PER_TILE
    private const val CANDIDATES = 512
    private const val INFLUENCE = 12f
    private const val LAMP_REACH = SubstratumLevels.SIGHT_CHUNKS * 16.0

    private const val SLOTS = SIDE / 16
    private const val UPLOADS_PER_FRAME = 24
    private const val EMPTY = Long.MIN_VALUE

    private var texture = -1
    private var lampBuffer = -1
    private val lampData = MemoryUtil.memAllocFloat(LAMPS * 4)
    private var tileTexture = -1
    private var tiles = MemoryUtil.memAlloc(0)
    private var rows = ByteArray(0)
    private val view = FrustumIntersection()
    var tilesX = 0
        private set
    private var tilesY = 0
    private var edgesX = FloatArray(0)
    private var edgesY = FloatArray(0)
    private val nearest = ArrayList<Long>()
    private val lamps = DoubleArray(CANDIDATES * 4)
    private var count = 0
    private var level: ClientLevel? = null
    private val slots = LongArray(SLOTS * SLOTS) { EMPTY }
    private val dirty = LongOpenHashSet()
    private val column = MemoryUtil.memAlloc(16 * 16 * HEIGHT)

    fun markDirty(cx: Int, cz: Int) {
        dirty.add(ChunkPos.asLong(cx, cz))
    }

    fun update(level: ClientLevel, centre: ChunkPos, radius: Int) {
        if (texture < 0) texture = create()
        if (level !== this.level) {
            this.level = level
            slots.fill(EMPTY)
            dirty.clear()
        }
        dirty.removeIf(LongPredicate { key ->
            val far = maxOf(Math.abs(ChunkPos.getX(key) - centre.x), Math.abs(ChunkPos.getZ(key) - centre.z)) > radius
            if (far && slots[slot(ChunkPos.getX(key), ChunkPos.getZ(key))] == key) slots[slot(ChunkPos.getX(key), ChunkPos.getZ(key))] = EMPTY
            far
        })
        var uploads = 0
        for (ring in 0..radius) for (cx in centre.x - ring..centre.x + ring) for (cz in centre.z - ring..centre.z + ring) {
            if (maxOf(Math.abs(cx - centre.x), Math.abs(cz - centre.z)) != ring) continue
            val key = ChunkPos.asLong(cx, cz)
            if (slots[slot(cx, cz)] == key && !dirty.contains(key)) continue
            if (uploads == 0) tightUnpack()
            upload(level, cx, cz)
            if (++uploads == UPLOADS_PER_FRAME) return
        }
    }

    fun selectLamps(level: ClientLevel, camera: Vec3) {
        nearest.clear()
        val reach = LAMP_REACH * LAMP_REACH
        LightField.forEachSource { pos, strength ->
            if (strength > 0f && distanceSq(pos, camera) < reach) nearest.add(pos)
        }
        nearest.sortBy { distanceSq(it, camera) }
        count = minOf(nearest.size, CANDIDATES)
        for (i in 0 until count) {
            val pos = BlockPos.of(nearest[i])
            val state = level.getBlockState(pos)
            val lamp = state.`is`(ModBlocks.lampBlock)
            val strength = if (lamp) LightField.strengthAt(nearest[i]) else state.lightEmission / 15f
            lamps[i * 4] = pos.x + 0.5
            lamps[i * 4 + 1] = pos.y + if (lamp) 0.0 else 0.5
            lamps[i * 4 + 2] = pos.z + 0.5
            lamps[i * 4 + 3] = (if (lamp) strength else -strength).toDouble()
        }
    }

    fun uploadLamps(camera: Vec3, viewProjection: Matrix4f, width: Int, height: Int) {
        resizeTiles(width, height)
        for (tile in 0 until tilesX * tilesY) rows[tile * ROW] = 0
        edges(viewProjection, width, height)
        view.set(viewProjection)
        lampData.clear()
        var shown = 0
        for (i in 0 until count) {
            if (shown == LAMPS) break
            val x = (lamps[i * 4] - camera.x).toFloat()
            val y = (lamps[i * 4 + 1] - camera.y).toFloat()
            val z = (lamps[i * 4 + 2] - camera.z).toFloat()
            if (!view.testSphere(x, y, z, INFLUENCE)) continue
            lampData.put(x).put(y).put(z).put(lamps[i * 4 + 3].toFloat())
            cover(shown, x, y, z)
            shown++
        }
        while (lampData.hasRemaining()) lampData.put(0f)
        lampData.flip()
        if (lampBuffer < 0) lampBuffer = GL15.glGenBuffers()
        GL15.glBindBuffer(GL31.GL_UNIFORM_BUFFER, lampBuffer)
        GL15.glBufferData(GL31.GL_UNIFORM_BUFFER, lampData, GL15.GL_STREAM_DRAW)
        GL15.glBindBuffer(GL31.GL_UNIFORM_BUFFER, 0)
        val active = GlStateManager._getActiveTexture()
        GlStateManager._activeTexture(GL13.GL_TEXTURE0 + TILE_UNIT)
        GlStateManager._bindTexture(tileTexture)
        tiles.clear()
        tiles.put(rows).flip()
        tightUnpack()
        GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, ROW / 4, tilesX * tilesY, GL30.GL_RGBA_INTEGER, GL11.GL_UNSIGNED_BYTE, tiles)
        GlStateManager._bindTexture(0)
        GlStateManager._activeTexture(active)
    }

    fun attach(program: Int) {
        val block = GL31.glGetUniformBlockIndex(program, "LampBlock")
        check(block != GL31.GL_INVALID_INDEX) { "substratum_light declares no LampBlock" }
        GL31.glUniformBlockBinding(program, block, LAMP_BLOCK)
    }

    private fun cover(index: Int, x: Float, y: Float, z: Float) {
        val x0 = first(edgesX, tilesX, x, y, z)
        val x1 = last(edgesX, tilesX, x, y, z)
        val y0 = first(edgesY, tilesY, x, y, z)
        val y1 = last(edgesY, tilesY, x, y, z)
        for (ty in y0..y1) {
            var row = (ty * tilesX + x0) * ROW
            repeat(x1 - x0 + 1) {
                val used = rows[row].toInt()
                if (used < PER_TILE) {
                    rows[row + 4 + used] = index.toByte()
                    rows[row] = (used + 1).toByte()
                }
                row += ROW
            }
        }
    }

    private fun first(edges: FloatArray, tileCount: Int, x: Float, y: Float, z: Float): Int {
        var tile = 0
        while (tile < tileCount - 1 && side(edges, tile + 1, x, y, z) > INFLUENCE) tile++
        return tile
    }

    private fun last(edges: FloatArray, tileCount: Int, x: Float, y: Float, z: Float): Int {
        var tile = tileCount - 1
        while (tile > 0 && side(edges, tile, x, y, z) < -INFLUENCE) tile--
        return tile
    }

    private fun side(edges: FloatArray, i: Int, x: Float, y: Float, z: Float): Float =
        edges[i * 4] * x + edges[i * 4 + 1] * y + edges[i * 4 + 2] * z + edges[i * 4 + 3]

    private fun edges(m: Matrix4f, width: Int, height: Int) {
        for (i in 0..tilesX) plane(edgesX, i, m.m00(), m.m10(), m.m20(), m.m30(), m, 2f * minOf(i * TILE, width) / width - 1f)
        for (i in 0..tilesY) plane(edgesY, i, m.m01(), m.m11(), m.m21(), m.m31(), m, 2f * minOf(i * TILE, height) / height - 1f)
    }

    private fun plane(out: FloatArray, i: Int, x: Float, y: Float, z: Float, w: Float, m: Matrix4f, at: Float) {
        val nx = x - at * m.m03()
        val ny = y - at * m.m13()
        val nz = z - at * m.m23()
        val scale = 1f / sqrt(nx * nx + ny * ny + nz * nz)
        out[i * 4] = nx * scale
        out[i * 4 + 1] = ny * scale
        out[i * 4 + 2] = nz * scale
        out[i * 4 + 3] = (w - at * m.m33()) * scale
    }

    private fun resizeTiles(width: Int, height: Int) {
        val across = (width + TILE - 1) / TILE
        val down = (height + TILE - 1) / TILE
        if (across == tilesX && down == tilesY && tileTexture >= 0) return
        tilesX = across
        tilesY = down
        edgesX = FloatArray((across + 1) * 4)
        edgesY = FloatArray((down + 1) * 4)
        MemoryUtil.memFree(tiles)
        tiles = MemoryUtil.memCalloc(ROW * across * down)
        rows = ByteArray(ROW * across * down)
        if (tileTexture < 0) tileTexture = GlStateManager._genTexture()
        val active = GlStateManager._getActiveTexture()
        GlStateManager._activeTexture(GL13.GL_TEXTURE0 + TILE_UNIT)
        GlStateManager._bindTexture(tileTexture)
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST)
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST)
        tightUnpack()
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGBA8UI, ROW / 4, across * down, 0, GL30.GL_RGBA_INTEGER, GL11.GL_UNSIGNED_BYTE, tiles)
        GlStateManager._bindTexture(0)
        GlStateManager._activeTexture(active)
    }

    fun bind() {
        val active = GlStateManager._getActiveTexture()
        GlStateManager._activeTexture(GL13.GL_TEXTURE0 + UNIT)
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, texture)
        GlStateManager._activeTexture(GL13.GL_TEXTURE0 + TILE_UNIT)
        GlStateManager._bindTexture(tileTexture)
        GlStateManager._activeTexture(active)
        GL30.glBindBufferBase(GL31.GL_UNIFORM_BUFFER, LAMP_BLOCK, lampBuffer)
    }

    fun unbind() {
        val active = GlStateManager._getActiveTexture()
        GlStateManager._activeTexture(GL13.GL_TEXTURE0 + UNIT)
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, 0)
        GlStateManager._activeTexture(GL13.GL_TEXTURE0 + TILE_UNIT)
        GlStateManager._bindTexture(0)
        GlStateManager._activeTexture(active)
        GL30.glBindBufferBase(GL31.GL_UNIFORM_BUFFER, LAMP_BLOCK, 0)
        GL15.glBindBuffer(GL31.GL_UNIFORM_BUFFER, 0)
    }

    private fun distanceSq(pos: Long, camera: Vec3): Double {
        val dx = BlockPos.getX(pos) + 0.5 - camera.x
        val dy = BlockPos.getY(pos) + 0.5 - camera.y
        val dz = BlockPos.getZ(pos) + 0.5 - camera.z
        return dx * dx + dy * dy + dz * dz
    }

    private fun upload(level: ClientLevel, cx: Int, cz: Int) {
        val key = ChunkPos.asLong(cx, cz)
        dirty.remove(key)
        slots[slot(cx, cz)] = key
        val engine = level.lightEngine.getLayerListener(LightLayer.BLOCK)
        column.clear()
        for (y in 0 until HEIGHT) {
            val worldY = BASE_Y + y
            val layer = engine.getDataLayerData(SectionPos.of(cx, worldY shr 4, cz))
            for (z in 0 until 16) for (x in 0 until 16) {
                column.put(((layer?.get(x, worldY and 15, z) ?: 0) * 17).toByte())
            }
        }
        column.flip()
        val active = GlStateManager._getActiveTexture()
        GlStateManager._activeTexture(GL13.GL_TEXTURE0 + UNIT)
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, texture)
        GL12.glTexSubImage3D(GL12.GL_TEXTURE_3D, 0, Math.floorMod(cx, SLOTS) * 16, Math.floorMod(cz, SLOTS) * 16, 0, 16, 16, HEIGHT, GL11.GL_RED, GL11.GL_UNSIGNED_BYTE, column)
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, 0)
        GlStateManager._activeTexture(active)
    }

    private fun tightUnpack() {
        GlStateManager._pixelStore(GL11.GL_UNPACK_ROW_LENGTH, 0)
        GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_ROWS, 0)
        GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_PIXELS, 0)
        GlStateManager._pixelStore(GL11.GL_UNPACK_ALIGNMENT, 1)
        GlStateManager._pixelStore(GL12.GL_UNPACK_IMAGE_HEIGHT, 0)
        GlStateManager._pixelStore(GL12.GL_UNPACK_SKIP_IMAGES, 0)
    }

    private fun slot(cx: Int, cz: Int): Int = Math.floorMod(cx, SLOTS) * SLOTS + Math.floorMod(cz, SLOTS)

    private fun create(): Int {
        val id = GL11.glGenTextures()
        val active = GlStateManager._getActiveTexture()
        GlStateManager._activeTexture(GL13.GL_TEXTURE0 + UNIT)
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, id)
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR)
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR)
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT)
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_REPEAT)
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL12.GL_TEXTURE_WRAP_R, GL13.GL_CLAMP_TO_BORDER)
        tightUnpack()
        val zeros = MemoryUtil.memCalloc(SIDE * SIDE * HEIGHT)
        GL12.glTexImage3D(GL12.GL_TEXTURE_3D, 0, GL30.GL_R8, SIDE, SIDE, HEIGHT, 0, GL11.GL_RED, GL11.GL_UNSIGNED_BYTE, zeros)
        MemoryUtil.memFree(zeros)
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, 0)
        GlStateManager._activeTexture(active)
        return id
    }
}
