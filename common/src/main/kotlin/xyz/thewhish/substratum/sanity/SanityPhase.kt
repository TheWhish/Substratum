package xyz.thewhish.substratum.sanity

enum class SanityPhase(val startTick: Int) {
    QUIET(0),

    DISORIENTATION(5 * 60 * 20),

    HUNT(10 * 60 * 20);

    companion object {
        private val DESCENDING = entries.sortedByDescending { it.startTick }

        fun of(ticks: Int): SanityPhase = DESCENDING.firstOrNull { ticks >= it.startTick } ?: QUIET
    }
}
