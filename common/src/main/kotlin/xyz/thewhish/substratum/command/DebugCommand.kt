package xyz.thewhish.substratum.command

import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component
import xyz.thewhish.substratum.gaze.Probe
import xyz.thewhish.substratum.level.LampBursts
import xyz.thewhish.substratum.network.VhsSync
import xyz.thewhish.substratum.smiler.SmilerDirector
import xyz.thewhish.substratum.smiler.Spots

object DebugCommand {


    fun node(): LiteralArgumentBuilder<CommandSourceStack> {
        val visit = Commands.literal("visit").executes { visit(it, null) }
        for (approach in Spots.Approach.entries) {
            visit.then(Commands.literal(approach.name.lowercase()).executes { visit(it, approach) })
        }
        return Commands.literal("debug")
            .then(visit)
            .then(Commands.literal("clear").executes(::clear))
            .then(
                Commands.literal("gaze")
                    .executes(::gaze)
                    .then(Commands.literal("freeze").executes(::freezeGaze))
                    .then(Commands.literal("off").executes(::stopGaze))
            )
            .then(Commands.literal("glitch").executes(::glitch))
            .then(Commands.literal("burst").executes(::burst))
    }

    private fun visit(context: CommandContext<CommandSourceStack>, approach: Spots.Approach?): Int {
        val player = context.source.playerOrException
        if (!SmilerDirector.conjure(player, approach)) return fail(context, "No place for a smiler here: stand on the Level 0 floor with open corridors around")
        return done(context, "A smiler is coming (${approach?.name?.lowercase() ?: "any approach"})")
    }

    private fun clear(context: CommandContext<CommandSourceStack>): Int {
        val player = context.source.playerOrException
        val removed = SmilerDirector.clear(player.serverLevel())
        return done(context, "Removed $removed smilers")
    }

    private fun gaze(context: CommandContext<CommandSourceStack>): Int {
        if (!Probe.toggle(context.source.playerOrException)) return done(context, "Проверка движка зрения выключена")
        return done(context, "Проверка движка зрения включена. Ходи и крутись: вердикт в левом верхнем углу. Повтор команды — выключить")
    }

    private fun freezeGaze(context: CommandContext<CommandSourceStack>): Int {
        Probe.freeze(context.source.playerOrException)
        return done(context, "Снимок взгляда заморожен: отойди и сравни точки с тем, что видно с белой точки")
    }

    private fun stopGaze(context: CommandContext<CommandSourceStack>): Int {
        Probe.stop(context.source.playerOrException)
        return done(context, "Проверка движка зрения выключена")
    }

    private fun glitch(context: CommandContext<CommandSourceStack>): Int {
        VhsSync.send(context.source.playerOrException)
        return done(context, "Glitch (Level 0 only)")
    }

    private fun burst(context: CommandContext<CommandSourceStack>): Int {
        val pos = LampBursts.burstNear(context.source.playerOrException)
            ?: return fail(context, "No lamp to burst: in Level 0 it needs a burning lamp within 24 blocks that you can see")
        return done(context, "A lamp bursts at ${pos.x} ${pos.y} ${pos.z}")
    }

    private fun done(context: CommandContext<CommandSourceStack>, message: String): Int {
        context.source.sendSuccess({ Component.literal(message) }, false)
        return 1
    }

    private fun fail(context: CommandContext<CommandSourceStack>, message: String): Int {
        context.source.sendFailure(Component.literal(message))
        return 0
    }
}
