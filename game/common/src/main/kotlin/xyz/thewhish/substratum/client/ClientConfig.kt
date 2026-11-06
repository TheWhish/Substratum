package xyz.thewhish.substratum.client

import xyz.thewhish.substratum.config.ConfigFile

object ClientConfig {

    private val file = ConfigFile(
        "substratum-client.toml",
        """
        [effects]
        # 0.0 turns every Level 0 post-processing effect off, 1.0 is full strength
        intensity = 1.0
        # true removes every field of view change: breathing and the squeeze of a panic attack
        motion_sickness_safe = false
        """.trimIndent() + "\n"
    )

    var intensity = 1f
        private set
    var motionSicknessSafe = false
        private set

    fun load() {
        file.load()
        intensity = file.double("effects.intensity", 1.0, 0.0..1.0).toFloat()
        motionSicknessSafe = file.boolean("effects.motion_sickness_safe", false)
    }
}
