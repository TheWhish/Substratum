package xyz.thewhish.substratum.rift.client

import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.block.model.BakedQuad
import net.minecraft.core.Direction
import net.minecraft.util.RandomSource
import net.minecraft.world.level.block.state.BlockState
import xyz.thewhish.substratum.rift.RiftCutBlock

object RiftCutQuads {

    private val LOW = ((1.0 - RiftCutBlock.CUT) / 2.0).toFloat()
    private val HIGH = (1.0f - LOW)

    private const val WALL_SEED = 0x51177L

    fun of(cut: BlockState, host: BlockState, side: Direction?, random: RandomSource): List<BakedQuad> {
        val quads = faces(cut, host, side, random)
        return if (side == null) quads + walls(cut, host) else quads
    }

    fun isCut(cut: BlockState, side: Direction?): Boolean =
        cut.getValue(RiftCutBlock.THROUGH) || side == cut.getValue(RiftCutBlock.MOUTH)

    fun faces(cut: BlockState, host: BlockState, side: Direction?, random: RandomSource): List<BakedQuad> {
        val model = Minecraft.getInstance().blockRenderer.getBlockModel(host)
        val source = model.getQuads(host, side, random)
        if (!isCut(cut, side)) return source
        val narrow = narrow(RiftCutBlock.axisOf(cut))
        val quads = ArrayList<BakedQuad>(source.size * 2)
        for (quad in source) {
            QuadClipper.clip(quad, narrow, LOW, keepLess = true, quads)
            QuadClipper.clip(quad, narrow, HIGH, keepLess = false, quads)
        }
        return quads
    }

    fun narrow(axis: Direction.Axis): Direction.Axis =
        if (axis == Direction.Axis.X) Direction.Axis.Z else Direction.Axis.X

    fun walls(cut: BlockState, host: BlockState): List<BakedQuad> {
        if (!cut.getValue(RiftCutBlock.THROUGH)) return emptyList()
        val narrow = narrow(RiftCutBlock.axisOf(cut))
        val model = Minecraft.getInstance().blockRenderer.getBlockModel(host)
        val out = ArrayList<BakedQuad>()
        val random = RandomSource.create(WALL_SEED)
        val far = Direction.fromAxisAndDirection(narrow, Direction.AxisDirection.POSITIVE)
        for (quad in model.getQuads(host, far, random)) out.add(QuadClipper.translate(quad, narrow, LOW - 1.0f))

        random.setSeed(WALL_SEED)
        val near = Direction.fromAxisAndDirection(narrow, Direction.AxisDirection.NEGATIVE)
        for (quad in model.getQuads(host, near, random)) out.add(QuadClipper.translate(quad, narrow, HIGH))
        return out
    }
}
