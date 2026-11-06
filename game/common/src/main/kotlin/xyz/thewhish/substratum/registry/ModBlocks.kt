package xyz.thewhish.substratum.registry

import dev.architectury.registry.registries.DeferredRegister
import dev.architectury.registry.registries.RegistrySupplier
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.Registries
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.util.RandomSource
import net.minecraft.util.StringRepresentable
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.LevelReader
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.RenderShape
import net.minecraft.world.level.block.SoundType
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.BooleanProperty
import net.minecraft.world.level.block.state.properties.EnumProperty
import net.minecraft.world.level.block.state.properties.IntegerProperty
import net.minecraft.world.level.material.MapColor
import net.minecraft.world.level.material.PushReaction
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.VoxelShape
import xyz.thewhish.substratum.Substratum
import xyz.thewhish.substratum.client.LampFlicker
import xyz.thewhish.substratum.level.AbyssGuard
import xyz.thewhish.substratum.level.BackroomsGuard
import xyz.thewhish.substratum.level.LampBursts
import xyz.thewhish.substratum.level.PitExitGuard
import xyz.thewhish.substratum.rift.RiftCutBlock
import kotlin.math.roundToInt

object ModBlocks {
    private val BLOCKS: DeferredRegister<Block> = DeferredRegister.create(Substratum.ID, Registries.BLOCK)
    private val BLOCK_ITEMS: DeferredRegister<Item> = DeferredRegister.create(Substratum.ID, Registries.ITEM)

    const val WALL_VARIANTS = 29
    const val CARPET_VARIANTS = 29
    const val CEILING_VARIANTS = 17

    const val SHAFT_FADE_VARIANTS = 3

    val WALL_VARIANT: IntegerProperty = IntegerProperty.create("variant", 0, WALL_VARIANTS - 1)
    val CARPET_VARIANT: IntegerProperty = IntegerProperty.create("variant", 0, CARPET_VARIANTS - 1)
    val CEILING_VARIANT: IntegerProperty = IntegerProperty.create("variant", 0, CEILING_VARIANTS - 1)
    val SHAFT_FADE_VARIANT: IntegerProperty = IntegerProperty.create("variant", 0, SHAFT_FADE_VARIANTS - 1)

    enum class LampCondition(private val id: String, val brightness: Float) : StringRepresentable {
        ON("on", 1f),
        FLICKERING("flickering", 1f),
        DIM("dim", 0.2f),
        DEAD("dead", 0f);

        val lit: Boolean get() = brightness > 0f

        override fun getSerializedName(): String = id
    }

    val LAMP_CONDITION: EnumProperty<LampCondition> = EnumProperty.create("condition", LampCondition::class.java)

    val CLEAN: BooleanProperty = BooleanProperty.create("clean")

    val DAMP_CARPET: RegistrySupplier<Block> = register("damp_carpet") { CleanableBlock(carpet()) }

    val SUB_FLOOR: RegistrySupplier<Block> = register("sub_floor") {
        Block(
            BlockBehaviour.Properties.of()
                .mapColor(MapColor.COLOR_GRAY)
                .strength(1.5f)
                .sound(SoundType.STONE)
                .noLootTable()
        )
    }

    val YELLOW_WALLPAPER: RegistrySupplier<Block> = register("yellow_wallpaper") { CleanableBlock(wall()) }

    val CEILING_TILE: RegistrySupplier<Block> = register("ceiling_tile") {
        CleanableBlock(wall().mapColor(MapColor.TERRACOTTA_YELLOW))
    }

    val FLUORESCENT_LAMP: RegistrySupplier<Block> = register("fluorescent_lamp") {
        LampBlock(
            BlockBehaviour.Properties.of()
                .mapColor(MapColor.TERRACOTTA_WHITE)
                .strength(1.5f)
                .sound(SoundType.GLASS)
                .lightLevel { (LAMP_LIGHT * it.getValue(LAMP_CONDITION).brightness).roundToInt() }
                .emissiveRendering { state, _, _ -> state.getValue(LAMP_CONDITION).lit }
                .noLootTable()
        )
    }

    val YELLOW_WALLPAPER_TOP: RegistrySupplier<Block> = register("yellow_wallpaper_top") { CleanableBlock(wall()) }

    val YELLOW_WALLPAPER_BOTTOM: RegistrySupplier<Block> = register("yellow_wallpaper_bottom") { CleanableBlock(wall()) }

    val WALL_ANOMALY: RegistrySupplier<Block> = register("wall_anomaly") { WallAnomalyBlock(wall()) }

    val CARPET_ANOMALY: RegistrySupplier<Block> = register("carpet_anomaly") { CarpetAnomalyBlock(carpet()) }

    val CEILING_ANOMALY: RegistrySupplier<Block> = register("ceiling_anomaly") {
        CeilingAnomalyBlock(wall().mapColor(MapColor.TERRACOTTA_YELLOW))
    }

    val RIFT_CUT: RegistrySupplier<Block> = BLOCKS.register("rift_cut") {
        RiftCutBlock(
            BlockBehaviour.Properties.of()
                .mapColor(MapColor.STONE)
                .strength(-1.0f, 3600000.0f)
                .noOcclusion()
                .noLootTable()
                .pushReaction(PushReaction.BLOCK)
                .dynamicShape()
        )
    }

    val SHAFT_FADE: RegistrySupplier<Block> = BLOCKS.register("shaft_fade") {
        ShaftFadeBlock(
            BlockBehaviour.Properties.of()
                .mapColor(MapColor.COLOR_BLACK)
                .strength(-1.0f, 3600000.0f)
                .noLootTable()
        )
    }

    val PIT_VOID: RegistrySupplier<Block> = BLOCKS.register("pit_void") {
        PitVoidBlock(
            BlockBehaviour.Properties.of()
                .mapColor(MapColor.COLOR_BLACK)
                .strength(-1.0f, 3600000.0f)
                .noCollission()
                .noLootTable()
        )
    }

    val ABYSS_FLOOR: RegistrySupplier<Block> = BLOCKS.register("abyss_floor") {
        AbyssFloorBlock(
            BlockBehaviour.Properties.of()
                .strength(-1.0f, 3600000.0f)
                .noLootTable()
                .noOcclusion()
                .pushReaction(PushReaction.BLOCK)
                .isValidSpawn { _, _, _, _ -> false }
        )
    }

    val ALMOND_WATER: RegistrySupplier<Block> = BLOCKS.register("almond_water") {
        AlmondWaterBlock(
            BlockBehaviour.Properties.of()
                .mapColor(MapColor.NONE)
                .instabreak()
                .noCollission()
                .noOcclusion()
                .offsetType(BlockBehaviour.OffsetType.XZ)
                .sound(SoundType.GLASS)
                .pushReaction(PushReaction.DESTROY)
                .noLootTable()
        )
    }

    fun dampCarpet(clean: Boolean): BlockState = DAMP_CARPET.get().defaultBlockState().setValue(CLEAN, clean)

    fun ceilingTile(clean: Boolean): BlockState = CEILING_TILE.get().defaultBlockState().setValue(CLEAN, clean)

    val lampBlock: Block by lazy { FLUORESCENT_LAMP.get() }

    fun lamp(condition: LampCondition): BlockState =
        lampBlock.defaultBlockState().setValue(LAMP_CONDITION, condition)

    fun isLamp(state: BlockState, condition: LampCondition): Boolean =
        state.`is`(lampBlock) && state.getValue(LAMP_CONDITION) == condition

    fun wallpaper(clean: Boolean): BlockState = YELLOW_WALLPAPER.get().defaultBlockState().setValue(CLEAN, clean)

    fun wallpaperTop(clean: Boolean): BlockState = YELLOW_WALLPAPER_TOP.get().defaultBlockState().setValue(CLEAN, clean)

    fun wallpaperBottom(clean: Boolean): BlockState =
        YELLOW_WALLPAPER_BOTTOM.get().defaultBlockState().setValue(CLEAN, clean)

    fun wallAnomaly(variant: Int): BlockState {
        require(variant in 0 until WALL_VARIANTS) { "wall_anomaly variant $variant is outside 0..${WALL_VARIANTS - 1}" }
        return WALL_ANOMALY.get().defaultBlockState().setValue(WALL_VARIANT, variant)
    }

    fun carpetAnomaly(variant: Int): BlockState {
        require(variant in 0 until CARPET_VARIANTS) {
            "carpet_anomaly variant $variant is outside 0..${CARPET_VARIANTS - 1}"
        }
        return CARPET_ANOMALY.get().defaultBlockState().setValue(CARPET_VARIANT, variant)
    }

    fun ceilingAnomaly(variant: Int): BlockState {
        require(variant in 0 until CEILING_VARIANTS) {
            "ceiling_anomaly variant $variant is outside 0..${CEILING_VARIANTS - 1}"
        }
        return CEILING_ANOMALY.get().defaultBlockState().setValue(CEILING_VARIANT, variant)
    }

    fun shaftFade(step: Int): BlockState {
        require(step in 1..SHAFT_FADE_VARIANTS) { "shaft_fade step $step is outside 1..$SHAFT_FADE_VARIANTS" }
        return SHAFT_FADE.get().defaultBlockState().setValue(SHAFT_FADE_VARIANT, step - 1)
    }

    fun register() {
        BLOCKS.register()
        BLOCK_ITEMS.register()
    }

    private class CleanableBlock(properties: Properties) : Block(properties) {
        override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
            builder.add(CLEAN)
        }
    }

    private class WallAnomalyBlock(properties: Properties) : Block(properties) {
        override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
            builder.add(WALL_VARIANT)
        }
    }

    private class CarpetAnomalyBlock(properties: Properties) : Block(properties) {
        override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
            builder.add(CARPET_VARIANT)
        }
    }

    private class CeilingAnomalyBlock(properties: Properties) : Block(properties) {
        override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
            builder.add(CEILING_VARIANT)
        }
    }

    private class ShaftFadeBlock(properties: Properties) : Block(properties) {
        override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
            builder.add(SHAFT_FADE_VARIANT)
        }
    }

    private class LampBlock(properties: Properties) : Block(properties) {
        override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
            builder.add(LAMP_CONDITION)
        }

        override fun animateTick(state: BlockState, level: Level, pos: BlockPos, random: RandomSource) {
            if (state.getValue(LAMP_CONDITION) != LampCondition.DIM || random.nextFloat() >= LAMP_CRACKLE_CHANCE) return
            level.playLocalSound(
                pos.x + 0.5,
                pos.y + 0.5,
                pos.z + 0.5,
                ModSounds.LAMP_CRACKLE.get(),
                SoundSource.BLOCKS,
                1.0f,
                0.9f + random.nextFloat() * 0.2f,
                false
            )
        }

        override fun triggerEvent(state: BlockState, level: Level, pos: BlockPos, id: Int, param: Int): Boolean {
            if (id != LampBursts.EVENT) return false
            if (level.isClientSide) LampFlicker.burst(level, pos)
            return true
        }

        override fun tick(state: BlockState, level: ServerLevel, pos: BlockPos, random: RandomSource) {
            LampBursts.shatter(level, pos, state)
        }
    }

    private class AlmondWaterBlock(properties: Properties) : Block(properties) {
        override fun getShape(state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext): VoxelShape {
            val offset = state.getOffset(level, pos)
            return BOTTLE_SHAPE.move(offset.x, offset.y, offset.z)
        }

        override fun getCloneItemStack(level: LevelReader, pos: BlockPos, state: BlockState): ItemStack =
            ItemStack(ModItems.ALMOND_WATER.get())

        override fun useWithoutItem(state: BlockState, level: Level, pos: BlockPos, player: Player, hit: BlockHitResult): InteractionResult {
            if (level.isClientSide) return InteractionResult.SUCCESS
            level.removeBlock(pos, false)
            BackroomsGuard.resyncRemoteWatchersAfterCrossPortalEdit(level, pos)
            player.inventory.placeItemBackInInventory(ItemStack(ModItems.ALMOND_WATER.get()))
            val pitch = ((level.random.nextFloat() - level.random.nextFloat()) * 0.7f + 1f) * 2f
            level.playSound(null, pos, SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.2f, pitch)
            return InteractionResult.CONSUME
        }
    }

    private class PitVoidBlock(properties: Properties) : Block(properties) {
        override fun entityInside(state: BlockState, level: Level, pos: BlockPos, entity: Entity) {
            if (entity is ServerPlayer) PitExitGuard.onShaftEntered(entity, pos)
        }
    }

    private class AbyssFloorBlock(properties: Properties) : Block(properties) {
        override fun getRenderShape(state: BlockState): RenderShape = RenderShape.INVISIBLE

        override fun fallOn(level: Level, state: BlockState, pos: BlockPos, entity: Entity, fallDistance: Float) =
            AbyssGuard.reject(entity, pos)

        override fun stepOn(level: Level, pos: BlockPos, state: BlockState, entity: Entity) =
            AbyssGuard.reject(entity, pos)
    }

    private fun wall(): BlockBehaviour.Properties = BlockBehaviour.Properties.of()
        .mapColor(MapColor.COLOR_YELLOW)
        .strength(1.5f)
        .sound(SoundType.WOOD)
        .noLootTable()

    private fun carpet(): BlockBehaviour.Properties = BlockBehaviour.Properties.of()
        .mapColor(MapColor.COLOR_YELLOW)
        .strength(0.1f)
        .sound(SoundType.WOOL)
        .noLootTable()

    private fun register(id: String, block: () -> Block): RegistrySupplier<Block> {
        val supplier = BLOCKS.register(id, block)
        BLOCK_ITEMS.register(id) { BlockItem(supplier.get(), Item.Properties()) }
        return supplier
    }

    private const val LAMP_LIGHT = 15

    private const val LAMP_CRACKLE_CHANCE = 1f / 14

    private val BOTTLE_SHAPE: VoxelShape = Block.box(5.0, 0.0, 5.0, 11.0, 8.0, 11.0)
}
