package xyz.thewhish.substratum.level

import dev.architectury.event.events.common.PlayerEvent
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.effect.MobEffects
import xyz.thewhish.substratum.config.ServerConfig
import java.util.UUID

object BackroomsDeath {
    private val respawningFromLevel0 = HashSet<UUID>()

    private const val EFFECT_TICKS = 100

    fun register() {
        PlayerEvent.PLAYER_CLONE.register { oldPlayer, newPlayer, wonGame ->
            if (wonGame || oldPlayer.level().dimension() != SubstratumLevels.LEVEL_0) return@register
            respawningFromLevel0.add(newPlayer.uuid)
            if (!ServerConfig.keepInventory) return@register
            newPlayer.inventory.replaceWith(oldPlayer.inventory)
            newPlayer.experienceLevel = oldPlayer.experienceLevel
            newPlayer.totalExperience = oldPlayer.totalExperience
            newPlayer.experienceProgress = oldPlayer.experienceProgress
            newPlayer.score = oldPlayer.score
        }
        PlayerEvent.PLAYER_RESPAWN.register { player, _, _ ->
            if (respawningFromLevel0.remove(player.uuid)) {
                player.addEffect(MobEffectInstance(MobEffects.DARKNESS, EFFECT_TICKS))
            }
        }
    }
}
