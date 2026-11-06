package xyz.thewhish.substratum.client

private const val LAMPS = 400
private const val TICKS = 24_000L

fun main() {
    var lit = 0L
    var bursts = 0L
    var outages = 0L
    var longest = 0L
    var worstSimultaneous = 0
    val offAt = IntArray(TICKS.toInt())
    val strikesByTemper = LongArray(3)
    val lampsByTemper = IntArray(3)
    repeat(LAMPS) { i ->
        val lamp = i * 0x9E3779B97F4AL + 17
        val temper = LampFlicker.temperament(lamp)
        lampsByTemper[temper]++
        var run = 0L
        var seenLit = false
        for (time in 0 until TICKS) {
            val on = LampFlicker.isLit(lamp, time)
            check(on == LampFlicker.isLit(lamp, time)) { "lamp $lamp is not deterministic at $time" }
            if (on) {
                lit++
                if (seenLit && run == 1L) error("lamp $lamp went dark for a single tick at $time")
                if (seenLit && run > 0) {
                    bursts++
                    strikesByTemper[temper]++
                }
                if (seenLit && run >= 40) outages++
                seenLit = true
                run = 0
            } else {
                run++
                longest = maxOf(longest, run)
                offAt[time.toInt()]++
            }
        }
    }
    offAt.forEach { worstSimultaneous = maxOf(worstSimultaneous, it) }
    val litShare = lit.toDouble() / (LAMPS * TICKS)
    val strikesPerMinute = bursts.toDouble() / LAMPS / (TICKS / 1200.0)
    check(litShare in 0.85..0.97) { "flickering lamps are lit ${"%.3f".format(litShare)} of the time" }
    val outagesPerMinute = outages.toDouble() / LAMPS / (TICKS / 1200.0)
    check(longest <= 130) { "a lamp stayed dark for $longest ticks" }
    check(outagesPerMinute in 0.4..1.2) { "${"%.2f".format(outagesPerMinute)} outages per lamp per minute" }
    check(strikesPerMinute in 10.0..40.0) { "${"%.1f".format(strikesPerMinute)} re-strikes per lamp per minute" }
    val perLamp = strikesByTemper.indices.map { strikesByTemper[it].toDouble() / lampsByTemper[it] / (TICKS / 1200.0) }
    check(lampsByTemper.all { it > LAMPS / 5 }) { "temperaments are lopsided: ${lampsByTemper.toList()}" }
    check(perLamp[0] * 2 < perLamp[1] && perLamp[1] * 1.5 < perLamp[2]) { "rare, usual and restless lamps must differ clearly: $perLamp" }
    check(worstSimultaneous < LAMPS / 4) { "$worstSimultaneous of $LAMPS lamps dark on the same tick — the phases have collapsed" }
    println(
        "LampFlicker: all checks passed — lit ${"%.1f".format(litShare * 100)}%, longest gap $longest ticks, " +
            "${"%.1f".format(strikesPerMinute)} re-strikes per minute, " +
            "${"%.2f".format(outagesPerMinute)} outages per minute, at most $worstSimultaneous/$LAMPS dark at once; " +
            "re-strikes by temperament ${perLamp.joinToString(" / ") { "%.1f".format(it) }} per minute."
    )
}
