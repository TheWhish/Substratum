package xyz.thewhish.substratum.rift

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.util.Mth
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.EntityBlock
import net.minecraft.world.level.block.RenderShape
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.BooleanProperty
import net.minecraft.world.level.block.state.properties.EnumProperty
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.EntityCollisionContext
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape
import kotlin.math.abs

class RiftCutBlock(properties: Properties) : Block(properties), EntityBlock {

    init {
        registerDefaultState(
            stateDefinition.any()
                .setValue(MOUTH, Direction.NORTH)
                .setValue(THROUGH, false)
        )
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(MOUTH, THROUGH)
    }

    override fun newBlockEntity(pos: BlockPos, state: BlockState) = RiftCutBlockEntity(pos, state)

    override fun getShape(
        state: BlockState,
        level: BlockGetter,
        pos: BlockPos,
        context: CollisionContext
    ): VoxelShape = shape(state, wide = false)

    override fun getCollisionShape(
        state: BlockState,
        level: BlockGetter,
        pos: BlockPos,
        context: CollisionContext
    ): VoxelShape {
        val axis = axisOf(state)
        val entity = (context as? EntityCollisionContext)?.entity
        if (entity is Player && (Squeeze.axisOf(entity) == axis || isSideways(entity.yRot, axis))) {
            return shape(state, wide = true)
        }
        return shape(state, wide = false)
    }

    override fun getRenderShape(state: BlockState): RenderShape = RenderShape.MODEL

    override fun skipRendering(state: BlockState, adjacent: BlockState, side: Direction): Boolean =
        adjacent.block is RiftCutBlock || super.skipRendering(state, adjacent, side)

    override fun getLightBlock(state: BlockState, level: BlockGetter, pos: BlockPos): Int =
        host(level, pos)?.getLightBlock(level, pos) ?: 15

    override fun propagatesSkylightDown(state: BlockState, level: BlockGetter, pos: BlockPos): Boolean =
        host(level, pos)?.propagatesSkylightDown(level, pos) ?: false

    override fun getShadeBrightness(state: BlockState, level: BlockGetter, pos: BlockPos): Float =
        host(level, pos)?.getShadeBrightness(level, pos) ?: FULL_BLOCK_SHADE

    private fun host(level: BlockGetter, pos: BlockPos): BlockState? =
        (level.getBlockEntity(pos) as? RiftCutBlockEntity)?.host

    companion object {
        val MOUTH: EnumProperty<Direction> = BlockStateProperties.HORIZONTAL_FACING

        val THROUGH: BooleanProperty = BooleanProperty.create("through")

        const val CUT = 0.2

        const val PASS = 0.65

        const val MID = 0.5

        private const val FULL_BLOCK_SHADE = 0.2f

        private const val SIDEWAYS = 0.45

        private const val CUT_EDGE = (1.0 - CUT) / 2.0
        private const val PASS_EDGE = (1.0 - PASS) / 2.0

        fun axisOf(state: BlockState): Direction.Axis = state.getValue(MOUTH).axis

        fun holds(entity: Entity, pos: BlockPos, axis: Direction.Axis): Boolean =
            void(axis).move(pos.x.toDouble(), pos.y.toDouble(), pos.z.toDouble()).intersects(entity.boundingBox)

        internal fun isSideways(yaw: Float, axis: Direction.Axis): Boolean {
            val radians = yaw * Mth.DEG_TO_RAD
            val along = if (axis == Direction.Axis.X) -Mth.sin(radians) else Mth.cos(radians)
            return abs(along) <= SIDEWAYS
        }

        private fun shape(state: BlockState, wide: Boolean): VoxelShape {
            val table = if (wide) PASS_SHAPES else CUT_SHAPES
            return table[if (state.getValue(THROUGH)) 0 else 1][state.getValue(MOUTH).ordinal]
        }

        private fun jambs(axis: Direction.Axis, edge: Double): VoxelShape = if (axis == Direction.Axis.X) {
            Shapes.or(Shapes.box(0.0, 0.0, 0.0, 1.0, 1.0, edge), Shapes.box(0.0, 0.0, 1.0 - edge, 1.0, 1.0, 1.0))
        } else {
            Shapes.or(Shapes.box(0.0, 0.0, 0.0, edge, 1.0, 1.0), Shapes.box(1.0 - edge, 0.0, 0.0, 1.0, 1.0, 1.0))
        }

        private fun seal(mouth: Direction): VoxelShape = when (mouth) {
            Direction.NORTH -> Shapes.box(0.0, 0.0, MID, 1.0, 1.0, 1.0)
            Direction.SOUTH -> Shapes.box(0.0, 0.0, 0.0, 1.0, 1.0, MID)
            Direction.WEST -> Shapes.box(MID, 0.0, 0.0, 1.0, 1.0, 1.0)
            else -> Shapes.box(0.0, 0.0, 0.0, MID, 1.0, 1.0)
        }

        private fun table(edge: Double): Array<Array<VoxelShape>> = arrayOf(
            Array(6) { i -> byOrdinal(i) { jambs(it.axis, edge) } },
            Array(6) { i -> byOrdinal(i) { Shapes.or(jambs(it.axis, edge), seal(it)) } }
        )

        private inline fun byOrdinal(ordinal: Int, build: (Direction) -> VoxelShape): VoxelShape {
            val direction = Direction.entries[ordinal]
            return if (direction.axis.isVertical) Shapes.block() else build(direction)
        }

        private val CUT_SHAPES = table(CUT_EDGE)
        private val PASS_SHAPES = table(PASS_EDGE)

        private val VOID_X = AABB(0.0, 0.0, PASS_EDGE, 1.0, 1.0, 1.0 - PASS_EDGE)
        private val VOID_Z = AABB(PASS_EDGE, 0.0, 0.0, 1.0 - PASS_EDGE, 1.0, 1.0)

        private fun void(axis: Direction.Axis): AABB = if (axis == Direction.Axis.X) VOID_X else VOID_Z
    }
}
