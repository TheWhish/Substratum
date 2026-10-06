package xyz.thewhish.substratum.command

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.context.CommandContext
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.ComponentUtils
import net.minecraft.network.chat.HoverEvent
import xyz.thewhish.substratum.level.SubstratumLevels
import xyz.thewhish.substratum.rift.RiftSpawner
import xyz.thewhish.substratum.sanity.SanityPhase
import xyz.thewhish.substratum.sanity.SanityTracker
import xyz.thewhish.substratum.worldgen.MazeChunkGenerator
import xyz.thewhish.substratum.worldgen.MazeLayout
import kotlin.math.roundToInt
import kotlin.math.sqrt

object SubstratumCommand {
    private const val FOOTING_RADIUS = 128
    private const val EXIT_SEARCH_RADIUS = 1024

    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(
            Commands.literal("substratum")
                .requires { it.hasPermission(2) }
                .then(Commands.literal("enter").executes(::enter))
                .then(DebugCommand.node())
                .then(Commands.literal("rift").then(Commands.literal("spawn").executes(::riftSpawn)))
                .then(Commands.literal("exit").then(Commands.literal("locate").executes(::exitLocate)))
                .then(
                    Commands.literal("sanity")
                        .then(Commands.literal("get").executes(::sanityGet))
                        .then(
                            Commands.literal("set").then(
                                Commands.argument("ticks", IntegerArgumentType.integer(0)).executes(::sanitySet)
                            )
                        )
                )
        )
    }

    private fun enter(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val player = source.playerOrException
        val level = source.server.getLevel(SubstratumLevels.LEVEL_0)
        if (level == null) {
            source.sendFailure(Component.translatable("commands.substratum.enter.missing"))
            return 0
        }
        val generator = level.chunkSource.generator as? MazeChunkGenerator
        val footing = generator?.layout?.findFooting(MazeLayout.CENTRE, MazeLayout.CENTRE, FOOTING_RADIUS, lit = true)
        val x = footing?.get(0) ?: MazeLayout.CENTRE
        val z = footing?.get(1) ?: MazeLayout.CENTRE
        player.teleportTo(level, x + 0.5, (MazeChunkGenerator.FLOOR_Y + 1).toDouble(), z + 0.5, player.yRot, player.xRot)
        source.sendSuccess({ Component.translatable("commands.substratum.enter.success", player.displayName) }, true)
        return 1
    }

    private fun riftSpawn(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val player = source.playerOrException
        val failure = when (RiftSpawner.forceSpawn(source.server, player)) {
            RiftSpawner.SpawnResult.Opened -> {
                source.sendSuccess({ Component.translatable("commands.substratum.rift.spawn.success", player.displayName) }, true)
                return 1
            }
            RiftSpawner.SpawnResult.Elsewhere -> "elsewhere"
            RiftSpawner.SpawnResult.NoCandidate -> "no_candidate"
            RiftSpawner.SpawnResult.NoTarget -> "no_target"
            RiftSpawner.SpawnResult.CutFailed -> "cut_failed"
        }
        source.sendFailure(Component.translatable("commands.substratum.rift.spawn.$failure"))
        return 0
    }

    private fun exitLocate(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val generator = source.level.chunkSource.generator as? MazeChunkGenerator
        if (generator == null) {
            source.sendFailure(Component.translatable("commands.substratum.exit.locate.elsewhere"))
            return 0
        }
        val from = BlockPos.containing(source.position)
        val hole = generator.nearestExit(from.x, from.z, EXIT_SEARCH_RADIUS)
        if (hole == null) {
            source.sendFailure(Component.translatable("commands.substratum.exit.locate.none", EXIT_SEARCH_RADIUS))
            return 0
        }
        val x = hole[0] + MazeLayout.PIT_HOLE / 2
        val z = hole[1] + MazeLayout.PIT_HOLE / 2
        val dx = (x - from.x).toDouble()
        val dz = (z - from.z).toDouble()
        val distance = sqrt(dx * dx + dz * dz).roundToInt()
        val coordinates = ComponentUtils.wrapInSquareBrackets(Component.translatable("chat.coordinates", x, "~", z)).withStyle {
            it.withColor(ChatFormatting.GREEN)
                .withClickEvent(ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/tp @s $x ~ $z"))
                .withHoverEvent(HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.translatable("chat.coordinates.tooltip")))
        }
        source.sendSuccess({ Component.translatable("commands.substratum.exit.locate.success", coordinates, distance) }, false)
        return 1
    }

    private fun sanityGet(context: CommandContext<CommandSourceStack>): Int {
        val ticks = SanityTracker.ticks(context.source.playerOrException)
        context.source.sendSuccess(
            { Component.translatable("commands.substratum.sanity.get", ticks, SanityPhase.of(ticks).name) },
            false
        )
        return 1
    }

    private fun sanitySet(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val ticks = IntegerArgumentType.getInteger(context, "ticks")
        if (!SanityTracker.set(source.playerOrException, ticks)) {
            source.sendFailure(Component.translatable("commands.substratum.sanity.elsewhere"))
            return 0
        }
        source.sendSuccess({ Component.translatable("commands.substratum.sanity.set", ticks, SanityPhase.of(ticks).name) }, true)
        return 1
    }
}
