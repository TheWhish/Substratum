package xyz.thewhish.substratum.level

import java.util.UUID

class RunState<T : Any> {
    var level = 0
        private set
    var area = 0
        private set
    var begun = false
        private set

    private val saved = HashMap<UUID, T>()
    private val departed = HashMap<UUID, T?>()
    private val lost = HashSet<UUID>()
    private val dying = HashMap<UUID, Int>()

    val save: Map<UUID, T> get() = saved

    fun load(level: Int, entries: Map<UUID, T>) {
        begun = false
        area = 0
        this.level = level
        saved.clear()
        saved.putAll(entries)
        departed.clear()
        lost.clear()
        dying.clear()
    }

    fun begin(): Boolean {
        if (begun) return false
        begun = true
        lost.clear()
        dying.clear()
        return true
    }

    fun entry(id: UUID): T? = saved[id]

    fun isLost(id: UUID): Boolean = id in lost

    fun isDying(id: UUID): Boolean = id in dying

    fun watchable(id: UUID): Boolean = id !in lost || id in dying

    fun fallen(online: Collection<UUID>): Boolean = begun && online.isNotEmpty() && online.all { it in lost && it !in dying }

    fun join(id: UUID): T? {
        lost.add(id)
        return if (departed.containsKey(id)) departed.remove(id) else saved[id]
    }

    fun leave(id: UUID, snapshot: T) {
        if (!begun) return
        departed[id] = snapshot
        lost.remove(id)
        dying.remove(id)
    }

    fun lose(id: UUID, until: Int? = null): Boolean {
        if (!begun || !lost.add(id)) return false
        if (until != null) dying[id] = until
        return true
    }

    fun expire(now: Int): List<UUID> {
        val done = dying.filterValues { now >= it }.keys.toList()
        done.forEach(dying::remove)
        return done
    }

    fun advance(online: Map<UUID, T>) {
        level++
        for ((id, entry) in departed) {
            if (entry == null) saved.remove(id) else saved[id] = entry
        }
        departed.clear()
        saved.putAll(online)
        restart()
    }

    fun wipe() {
        departed.replaceAll { _, _ -> null }
        restart()
    }

    private fun restart() {
        area++
        lost.clear()
        dying.clear()
    }
}
