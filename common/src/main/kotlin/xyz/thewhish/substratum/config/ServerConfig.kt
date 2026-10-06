package xyz.thewhish.substratum.config

import dev.architectury.event.events.common.LifecycleEvent
import xyz.thewhish.substratum.reality.Manifestation
import xyz.thewhish.substratum.smiler.Smiler

object ServerConfig {

    private const val TICKS_PER_SECOND = 20L
    private const val SECOND = 20

    private val file = ConfigFile(
        "substratum-server.toml",
        """
        [rifts]
        # false stops new rifts from opening, rifts that are already open still close as usual
        enabled = true
        # chance per in-game day (20 minutes) that a rift opens near a player
        chance_per_day = 0.1
        # in-game days before the same player can get another rift after theirs has closed
        cooldown_days = 3
        # seconds a rift stays open after someone has seen it
        seen_delay_seconds = 60

        [exits]
        # chance per in-game day that an exit crack opens near a player lost in Level 0
        chance_per_day = 0.35
        # seconds an exit crack stays open after someone has seen it
        seen_delay_seconds = 30

        [death]
        # nothing hurts in Level 0, /kill there keeps the inventory and experience
        keep_inventory = true

        [reality]
        # false keeps Level 0 calm: nothing new happens, what has already begun ends as usual
        enabled = true
        # seconds of silence between waves of happenings
        pause_min_seconds = 120
        pause_max_seconds = 480
        # how often the smiler comes compared to the default, 0 turns it off
        smiler_frequency = 1.0
        # pressure from which the smiler can come: 0 up to 5 minutes in Level 0, then grows to 1 at 10 minutes
        smiler_pressure = 0.15
        # blocks in a straight line between the smiler and the place where the player first sees it;
        # its face shines through the darkness up to 72 blocks and fades out by 80
        smiler_min_sight_distance = 64
        smiler_max_sight_distance = 72
        """.trimIndent() + "\n"
    )

    data class Pace(val frequency: Double, val pressure: Double)

    data class RealitySettings(
        val enabled: Boolean = true,
        val pause: IntRange = 120 * SECOND..480 * SECOND,
        val paces: Map<Manifestation, Pace> = Manifestation.entries.associateWith { Pace(1.0, it.pressure) },
        val smilerDistance: IntRange = 64..72,
    ) {
        fun pace(kind: Manifestation): Pace = paces.getValue(kind)
    }

    var riftsEnabled = true
        private set
    var riftChancePerDay = 0.1
        private set
    var riftCooldownDays = 3L
        private set
    var riftSeenDelayTicks = 60 * TICKS_PER_SECOND
        private set
    var exitChancePerDay = 0.35
        private set
    var exitSeenDelayTicks = 30 * TICKS_PER_SECOND
        private set
    var keepInventory = true
        private set
    var reality = RealitySettings()
        private set

    fun register() {
        LifecycleEvent.SERVER_BEFORE_START.register { load() }
    }

    private fun load() {
        file.load()
        riftsEnabled = file.boolean("rifts.enabled", true)
        riftChancePerDay = file.double("rifts.chance_per_day", 0.1, 0.0..1.0)
        riftCooldownDays = file.int("rifts.cooldown_days", 3, 0..365).toLong()
        riftSeenDelayTicks = file.int("rifts.seen_delay_seconds", 60, 0..3600) * TICKS_PER_SECOND
        exitChancePerDay = file.double("exits.chance_per_day", 0.35, 0.0..1.0)
        exitSeenDelayTicks = file.int("exits.seen_delay_seconds", 30, 0..3600) * TICKS_PER_SECOND
        keepInventory = file.boolean("death.keep_inventory", true)
        reality = loadReality()
    }

    private fun loadReality(): RealitySettings {
        val d = RealitySettings()
        val seconds = 10..3600
        val blocks = 16..Smiler.FACE_CLEAR.toInt()
        return RealitySettings(
            enabled = file.boolean("reality.enabled", d.enabled),
            pause = span(
                file.int("reality.pause_min_seconds", d.pause.first / SECOND, seconds),
                file.int("reality.pause_max_seconds", d.pause.last / SECOND, seconds),
                SECOND,
            ),
            paces = Manifestation.entries.associateWith { kind ->
                Pace(
                    file.double("reality.${kind.key}_frequency", d.pace(kind).frequency, 0.0..10.0),
                    file.double("reality.${kind.key}_pressure", d.pace(kind).pressure, 0.0..1.0),
                )
            },
            smilerDistance = span(
                file.int("reality.smiler_min_sight_distance", d.smilerDistance.first, blocks),
                file.int("reality.smiler_max_sight_distance", d.smilerDistance.last, blocks),
                1,
            ),
        )
    }

    private fun span(min: Int, max: Int, unit: Int): IntRange = min * unit..maxOf(min, max) * unit
}
