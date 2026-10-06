package xyz.thewhish.substratum.client

import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundSource
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.chunk.status.ChunkStatus
import qouteall.imm_ptl.core.ClientWorldLoader
import xyz.thewhish.substratum.level.LampBursts
import xyz.thewhish.substratum.registry.ModBlocks
import xyz.thewhish.substratum.registry.ModBlocks.LampCondition
import xyz.thewhish.substratum.registry.ModSounds

object LampFlicker {

    private const val RANGE = 32
    private const val SCAN_INTERVAL = 20
    private const val SOUND_RANGE = 20.0

    private const val WINDOW = 64L
    private const val OUTAGE_WINDOW = 400L
    private const val OUTAGE_MIN = 40L
    private const val OUTAGE_SPREAD = 61L
    private const val FLASH_PEAK = 1.6f
    private val GOLDEN = 0x9E3779B97F4A7C15uL.toLong()
    private val BURST_ODDS = longArrayOf(30L, 110L, 220L)
    private val OUTAGE_ODDS = longArrayOf(20L, 64L, 120L)

    private val lamps = HashSet<BlockPos>()
    private val off = HashSet<BlockPos>()
    private val bursts = HashMap<BlockPos, Int>()
    private var level: ClientLevel? = null
    private var ticks = 0

    fun tick(minecraft: Minecraft) {
        val current = minecraft.level
        val player = minecraft.player
        if (current !== level || player == null) {
            level?.takeIf(::stillShown)?.let { old ->
                off.forEach { switchTo(old, it, LampCondition.FLICKERING) }
                bursts.keys.forEach { LightField.boost(old, it, 1f) }
            }
            lamps.clear()
            off.clear()
            bursts.clear()
            level = current
            ticks = 0
        }
        if (current == null || player == null) return
        val eye = player.eyePosition
        if (ticks++ % SCAN_INTERVAL == 0) rescan(current, BlockPos.containing(eye))
        val steady = minecraft.options.hideLightningFlash().get()
        flash(current, steady)
        val time = current.gameTime
        val iterator = lamps.iterator()
        while (iterator.hasNext()) {
            val pos = iterator.next()
            val condition = conditionAt(current, pos)
            if (condition == LampCondition.FLICKERING) off.remove(pos)
            else if (condition != LampCondition.DEAD || pos !in off) {
                off.remove(pos)
                iterator.remove()
                continue
            }
            val lit = steady || isLit(pos.asLong(), time)
            if (lit == (pos !in off)) continue
            if (lit) off.remove(pos) else off.add(pos)
            switchTo(current, pos, if (lit) LampCondition.FLICKERING else LampCondition.DEAD)
            if (lit && !steady && pos.distToCenterSqr(eye) <= SOUND_RANGE * SOUND_RANGE) strike(current, pos)
        }
    }

    fun isOff(pos: BlockPos): Boolean = pos in off

    private fun rescan(level: ClientLevel, centre: BlockPos) {
        val reach = RANGE.toDouble() * RANGE
        val found = scanBlocks(level, centre, RANGE) { ModBlocks.isLamp(it, LampCondition.FLICKERING) }.toHashSet()
        val iterator = off.iterator()
        while (iterator.hasNext()) {
            val pos = iterator.next()
            if (pos.distSqr(centre) <= reach) continue
            iterator.remove()
            switchTo(level, pos, LampCondition.FLICKERING)
        }
        found.addAll(off)
        lamps.retainAll(found)
        lamps.addAll(found)
    }

    fun burst(level: Level, pos: BlockPos) {
        val current = this.level ?: return
        if (level !== current) return
        val at = pos.immutable()
        lamps.remove(at)
        off.remove(at)
        bursts[at] = 0
        play(current, pos, ModSounds.LAMP_POP.get())
    }

    private fun flash(level: ClientLevel, steady: Boolean) {
        val iterator = bursts.entries.iterator()
        while (iterator.hasNext()) {
            val burst = iterator.next()
            val age = burst.value + 1
            burst.setValue(age)
            if (age < LampBursts.FLASH_TICKS) {
                if (!steady) LightField.boost(level, burst.key, 1f + (FLASH_PEAK - 1f) * age / (LampBursts.FLASH_TICKS - 1))
                continue
            }
            iterator.remove()
            LightField.boost(level, burst.key, 1f)
        }
    }

    internal fun stillShown(level: ClientLevel): Boolean =
        ClientWorldLoader.getIsInitialized() && ClientWorldLoader.getClientWorlds().any { it === level }

    private fun conditionAt(level: ClientLevel, pos: BlockPos): LampCondition? =
        level.getBlockState(pos).takeIf { it.`is`(ModBlocks.lampBlock) }?.getValue(ModBlocks.LAMP_CONDITION)

    private fun switchTo(level: ClientLevel, pos: BlockPos, condition: LampCondition) {
        val chunk = level.chunkSource.getChunk(pos.x shr 4, pos.z shr 4, ChunkStatus.FULL, false) ?: return
        val index = chunk.getSectionIndex(pos.y)
        if (index !in chunk.sections.indices) return
        val section = chunk.sections[index]
        val old = section.getBlockState(pos.x and 15, pos.y and 15, pos.z and 15)
        val state = ModBlocks.lamp(condition)
        if (old === state || !old.`is`(ModBlocks.lampBlock)) return
        section.setBlockState(pos.x and 15, pos.y and 15, pos.z and 15, state)
        level.sendBlockUpdated(pos, old, state, Block.UPDATE_CLIENTS)
        LightField.lampChanged(level, pos)
    }

    fun isLit(lamp: Long, time: Long): Boolean {
        val key = mix(lamp)
        val temper = temperament(lamp)
        return !inOutage(key, time, OUTAGE_ODDS[temper]) && !inBurstGap(key, time, BURST_ODDS[temper])
    }

    internal fun temperament(lamp: Long): Int = Math.floorMod(mix(lamp xor GOLDEN) ushr 40, BURST_ODDS.size.toLong()).toInt()

    private fun inOutage(key: Long, time: Long, odds: Long): Boolean {
        val shifted = time + ((key ushr 16) and 0xFFFFL)
        val window = Math.floorDiv(shifted, OUTAGE_WINDOW)
        val roll = mix(key.inv() + window * GOLDEN)
        if ((roll and 0xFFL) >= odds) return false
        val length = OUTAGE_MIN + Math.floorMod(roll ushr 8, OUTAGE_SPREAD)
        val start = Math.floorMod(roll ushr 24, OUTAGE_WINDOW - length)
        val local = shifted - window * OUTAGE_WINDOW
        return local >= start && local < start + length
    }

    private fun inBurstGap(key: Long, time: Long, odds: Long): Boolean {
        val shifted = time + (key and 0xFFFFL)
        val window = Math.floorDiv(shifted, WINDOW)
        val roll = mix(key + window * GOLDEN)
        if ((roll and 0xFFL) >= odds) return false
        val local = shifted - window * WINDOW
        val gaps = 1 + ((roll ushr 11) and 3L) % 3
        val longFirst = ((roll ushr 13) and 7L) == 0L
        var at = (roll ushr 8) and 7L
        var bits = roll ushr 16
        for (gap in 0L until gaps) {
            val off = if (gap == 0L && longFirst) 12 + (bits and 15L) else 2 + (bits and 3L)
            if (local >= at && local < at + off) return true
            at += off + 2 + ((bits ushr 4) and 3L)
            bits = bits ushr 8
        }
        return false
    }

    private fun strike(level: ClientLevel, pos: BlockPos) = play(level, pos, ModSounds.LAMP_FLICKER.get())

    private fun play(level: ClientLevel, pos: BlockPos, sound: SoundEvent) {
        level.playLocalSound(
            pos.x + 0.5,
            pos.y + 0.5,
            pos.z + 0.5,
            sound,
            SoundSource.BLOCKS,
            1.0f,
            0.9f + level.random.nextFloat() * 0.2f,
            false
        )
    }

    private fun mix(value: Long): Long {
        var z = value * GOLDEN
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        return z xor (z ushr 31)
    }
}
