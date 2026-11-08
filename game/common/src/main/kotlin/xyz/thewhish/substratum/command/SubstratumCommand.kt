package xyz.thewhish.substratum.command

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.context.CommandContext
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component
import xyz.thewhish.substratum.level.Arrival
import xyz.thewhish.substratum.level.Run
import xyz.thewhish.substratum.level.SubstratumLevels
import xyz.thewhish.substratum.sanity.SanityPhase
import xyz.thewhish.substratum.sanity.SanityTracker

object SubstratumCommand {
    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(
            Commands.literal("substratum")
                .requires { it.hasPermission(2) }
                .then(Commands.literal("enter").executes(::enter))
                .then(Commands.literal("advance").executes(::advance))
                .then(DebugCommand.node())
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
        if (source.server.getLevel(SubstratumLevels.LEVEL_0) == null) {
            source.sendFailure(Component.translatable("commands.substratum.enter.missing"))
            return 0
        }
        Arrival.send(player)
        source.sendSuccess({ Component.translatable("commands.substratum.enter.success", player.displayName) }, true)
        return 1
    }

    private fun advance(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        Run.advance(source.server)
        source.sendSuccess({ Component.translatable("commands.substratum.advance", Run.level) }, true)
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
