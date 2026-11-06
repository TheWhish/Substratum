package xyz.thewhish.substratum.rift

import dev.architectury.event.EventResult
import dev.architectury.event.events.common.BlockEvent
import dev.architectury.event.events.common.LifecycleEvent
import dev.architectury.event.events.common.PlayerEvent
import net.minecraft.core.BlockPos
import net.minecraft.core.GlobalPos
import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.resources.ResourceKey
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.util.RandomSource
import net.minecraft.world.level.Level
import net.minecraft.world.level.saveddata.SavedData
import xyz.thewhish.substratum.Substratum
import xyz.thewhish.substratum.config.ServerConfig
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object RiftSpawnData {

    const val DAY_TICKS = 24000L

    private const val COOLDOWNS = "${Substratum.ID}_cooldowns"

    private class Mined(val pos: GlobalPos, val tick: Long)

    private class Cooldowns(val until: MutableMap<UUID, Long>) : SavedData() {
        override fun save(tag: CompoundTag, registries: HolderLookup.Provider): CompoundTag {
            until.forEach { (uuid, day) -> tag.putLong(uuid.toString(), day) }
            return tag
        }
    }

    @Suppress("TYPE_MISMATCH_BASED_ON_JAVA_ANNOTATIONS")
    private val FACTORY = SavedData.Factory({ Cooldowns(HashMap()) }, { tag, _ -> loadCooldowns(tag) }, null)

    private val mined = ConcurrentHashMap<UUID, Mined>()

    fun rollPerScan(random: RandomSource, chancePerDay: Double, scanTicks: Long): Boolean =
        random.nextDouble() < 1.0 - Math.pow(1.0 - chancePerDay, scanTicks.toDouble() / DAY_TICKS)

    fun onCooldown(player: ServerPlayer, currentDay: Long): Boolean {
        val cooldowns = cooldowns(player.server)
        val until = cooldowns.until[player.uuid] ?: return false
        if (until > currentDay) return true
        cooldowns.until.remove(player.uuid)
        cooldowns.setDirty()
        return false
    }

    fun onRiftClosed(server: MinecraftServer, owner: UUID, currentDay: Long) {
        val cooldowns = cooldowns(server)
        cooldowns.until[owner] = currentDay + ServerConfig.riftCooldownDays
        cooldowns.setDirty()
    }

    fun lastMined(player: ServerPlayer, dimension: ResourceKey<Level>): Pair<BlockPos, Long>? {
        val last = mined[player.uuid]?.takeIf { it.pos.dimension() == dimension } ?: return null
        return last.pos.pos() to last.tick
    }

    fun register() {
        LifecycleEvent.SERVER_BEFORE_START.register { mined.clear() }
        PlayerEvent.PLAYER_QUIT.register { mined.remove(it.uuid) }
        BlockEvent.BREAK.register { level, pos, _, player, _ ->
            if (level is ServerLevel) {
                mined[player.uuid] = Mined(GlobalPos.of(level.dimension(), pos.immutable()), level.server.tickCount.toLong())
            }
            EventResult.pass()
        }
    }

    private fun cooldowns(server: MinecraftServer): Cooldowns = server.overworld().dataStorage.computeIfAbsent(FACTORY, COOLDOWNS)

    private fun loadCooldowns(tag: CompoundTag): Cooldowns {
        val until = HashMap<UUID, Long>()
        for (key in tag.allKeys) {
            val uuid = runCatching { UUID.fromString(key) }.getOrNull() ?: continue
            until[uuid] = tag.getLong(key)
        }
        return Cooldowns(until)
    }
}
