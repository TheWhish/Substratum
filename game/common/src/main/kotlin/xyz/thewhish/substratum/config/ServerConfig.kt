package xyz.thewhish.substratum.config

import dev.architectury.event.events.common.LifecycleEvent
import xyz.thewhish.substratum.reality.Manifestation
import xyz.thewhish.substratum.smiler.Smiler

object ServerConfig {

    private const val SECOND = 20

    private val file = ConfigFile(
        "substratum-server.toml",
        """
        [reality]
        # false keeps Level 0 calm: nothing new happens, what has already begun ends as usual
        enabled = true
        # seconds of silence between waves of happenings
        pause_min_seconds = 90
        pause_max_seconds = 360
        # how often the smiler comes compared to the default, 0 turns it off
        smiler_frequency = 1.0
        # pressure from which the smiler can come: 0 up to 5 minutes in Level 0, then grows to 1 at 10 minutes
        smiler_pressure = 0.15
        # how often the smiler lies in wait around a tight corner and lunges at whoever turns it first, 0 turns it off
        ambush_frequency = 1.0
        # pressure from which it can lie in wait
        ambush_pressure = 0.35
        # blocks in a straight line between the smiler and the place where the player first sees it;
        # its face shines through the darkness up to 72 blocks and fades out by 80
        smiler_min_sight_distance = 64
        smiler_max_sight_distance = 72
        """.trimIndent() + "\n"
    )

    data class Pace(val frequency: Double, val pressure: Double)

    data class RealitySettings(
        val enabled: Boolean = true,
        val pause: IntRange = 90 * SECOND..360 * SECOND,
        val paces: Map<Manifestation, Pace> = Manifestation.entries.associateWith { Pace(1.0, it.pressure) },
        val ambush: Pace = Pace(1.0, 0.35),
        val smilerDistance: IntRange = 64..72,
    ) {
        fun pace(kind: Manifestation): Pace = paces.getValue(kind)
    }

    var reality = RealitySettings()
        private set

    fun register() {
        LifecycleEvent.SERVER_BEFORE_START.register { load() }
    }

    private fun load() {
        file.load()
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
            ambush = Pace(
                file.double("reality.ambush_frequency", d.ambush.frequency, 0.0..10.0),
                file.double("reality.ambush_pressure", d.ambush.pressure, 0.0..1.0),
            ),
            smilerDistance = span(
                file.int("reality.smiler_min_sight_distance", d.smilerDistance.first, blocks),
                file.int("reality.smiler_max_sight_distance", d.smilerDistance.last, blocks),
                1,
            ),
        )
    }

    private fun span(min: Int, max: Int, unit: Int): IntRange = min * unit..maxOf(min, max) * unit
}
