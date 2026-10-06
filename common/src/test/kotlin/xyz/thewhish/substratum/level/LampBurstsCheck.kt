package xyz.thewhish.substratum.level

import kotlin.random.Random

private const val RUNS = 2000
private const val MINUTE = 1200.0

private fun meanMinutes(pressure: Double, random: Random): Double {
    val chance = LampBursts.CHECK_INTERVAL * LampBursts.rate(pressure)
    var total = 0L
    repeat(RUNS) {
        var waited = LampBursts.PLAYER_COOLDOWN
        while (random.nextDouble() >= chance) waited += LampBursts.CHECK_INTERVAL
        total += waited
    }
    return total / RUNS / MINUTE
}

fun main() {
    val random = Random(7)
    val quiet = meanMinutes(0.0, random)
    val half = meanMinutes(0.5, random)
    val full = meanMinutes(1.0, random)
    val rates = (0..20).map { LampBursts.rate(it / 20.0) }
    check(rates.zipWithNext().all { (a, b) -> b > a }) { "burst rate must grow with pressure" }
    check(quiet in 9.0..11.5) { "quiet interval %.2f min is off the ~10 min target".format(quiet) }
    check(half in 5.5..7.0) { "half pressure interval %.2f min is off the ~6 min target".format(half) }
    check(full in 2.0..2.5) { "full pressure interval %.2f min is off the ~2.25 min target".format(full) }
    println("lamp bursts: p 0 %.2f min, p 0.5 %.2f min, p 1 %.2f min".format(quiet, half, full))
}
