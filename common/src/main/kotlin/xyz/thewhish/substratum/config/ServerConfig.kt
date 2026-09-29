package xyz.thewhish.substratum.config

import dev.architectury.event.events.common.LifecycleEvent

object ServerConfig {

    private const val TICKS_PER_SECOND = 20L

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
        # dying in Level 0 keeps the inventory and experience
        keep_inventory = true
        """.trimIndent() + "\n"
    )

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
    }
}
