package xyz.thewhish.substratum.client

import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import xyz.thewhish.substratum.level.Blackouts
import xyz.thewhish.substratum.level.SubstratumLevels
import xyz.thewhish.substratum.registry.ModBlocks
import xyz.thewhish.substratum.registry.ModBlocks.LampCondition

object Blackout {

    private const val EARLY_TOLERANCE = 100L

    private val MANAGED = setOf(LampCondition.ON, LampCondition.FLICKERING, LampCondition.FLICKERING_OFF, LampCondition.OUT, LampCondition.DIM)

    private class Tracked(val event: Blackouts.Event, val level: ClientLevel) {
        val inside = HashSet<Long>()
    }

    private val events = ArrayList<Tracked>()
    private val touched = HashSet<Long>()
    private val wanted = HashMap<Long, LampCondition>()
    private var level: ClientLevel? = null

    fun accept(event: Blackouts.Event) {
        val level = Minecraft.getInstance().level ?: return
        val now = level.gameTime
        if (event.start > now + EARLY_TOLERANCE || now - event.start >= Blackouts.DURATION) return
        events.removeIf { it.event.victim == event.victim }
        events.add(Tracked(event, level))
    }

    fun reset() {
        events.clear()
    }

    fun tick(minecraft: Minecraft) {
        val current = minecraft.level?.takeIf { it.dimension() == SubstratumLevels.LEVEL_0 }
        if (current !== level) {
            level?.takeIf(LampFlicker::stillShown)?.let(::restoreAll)
            touched.clear()
            level = current
        }
        if (current == null) return
        val now = current.gameTime
        events.removeIf { it.level !== current || now - it.event.start >= Blackouts.DURATION }
        if (events.isEmpty() && touched.isEmpty()) return
        wanted.clear()
        for (tracked in events) collect(current, tracked, now)
        apply(current)
    }

    private fun collect(level: ClientLevel, tracked: Tracked, now: Long) {
        val victim = level.getEntity(tracked.event.victim) ?: return
        val age = now - tracked.event.start
        val cursor = BlockPos.MutableBlockPos()
        LightField.forEachSource { key, _ ->
            cursor.set(key)
            val inside = key in tracked.inside
            if (!Blackouts.within(victim.x, victim.y, victim.z, cursor, Blackouts.reach(tracked.event, cursor, inside))) {
                if (inside) tracked.inside.remove(key)
                return@forEachSource
            }
            tracked.inside.add(key)
            Blackouts.role(tracked.event, cursor, age)?.let { wanted.putIfAbsent(key, it) }
        }
    }

    private fun apply(level: ClientLevel) {
        val cursor = BlockPos.MutableBlockPos()
        for (key in wanted.keys + touched) {
            cursor.set(key)
            val state = level.getBlockState(cursor)
            val condition = if (state.`is`(ModBlocks.lampBlock)) state.getValue(ModBlocks.LAMP_CONDITION) else null
            val ours = key in touched
            if (condition == null || (ours && condition !in MANAGED) || (!ours && condition != LampCondition.ON)) {
                touched.remove(key)
                continue
            }
            val want = wanted[key] ?: LampCondition.ON
            if (want == LampCondition.ON) touched.remove(key) else touched.add(key)
            if (condition == want || (want == LampCondition.FLICKERING && condition == LampCondition.FLICKERING_OFF)) continue
            LampFlicker.switchTo(level, cursor.immutable(), want)
        }
    }

    private fun restoreAll(level: ClientLevel) {
        for (key in touched) {
            val pos = BlockPos.of(key)
            val state = level.getBlockState(pos)
            if (!state.`is`(ModBlocks.lampBlock)) continue
            val condition = state.getValue(ModBlocks.LAMP_CONDITION)
            if (condition in MANAGED && condition != LampCondition.ON) LampFlicker.switchTo(level, pos, LampCondition.ON)
        }
    }
}
