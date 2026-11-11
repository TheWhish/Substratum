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

    val save: Map<UUID, T> get() = saved

    fun load(level: Int, entries: Map<UUID, T>) {
        begun = false
        area = 0
        this.level = level
        saved.clear()
        saved.putAll(entries)
        departed.clear()
        lost.clear()
    }

    fun begin(): Boolean {
        if (begun) return false
        begun = true
        lost.clear()
        return true
    }

    fun entry(id: UUID): T? = saved[id]

    fun isLost(id: UUID): Boolean = id in lost

    fun fallen(online: Collection<UUID>): Boolean = begun && online.isNotEmpty() && online.all { it in lost }

    fun join(id: UUID): T? {
        lost.add(id)
        return if (departed.containsKey(id)) departed.remove(id) else saved[id]
    }

    fun leave(id: UUID, snapshot: T) {
        if (!begun) return
        departed[id] = snapshot
        lost.remove(id)
    }

    fun lose(id: UUID): Boolean = begun && lost.add(id)

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
    }
}
