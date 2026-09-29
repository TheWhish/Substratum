package xyz.thewhish.substratum.level

import xyz.thewhish.substratum.sanity.SanityPhase
import kotlin.random.Random

private const val RUNS = 2000
private const val MINUTE = 1200.0

private fun meanMinutes(ticks: Int, random: Random): Double {
    val chance = LampBursts.CHECK_INTERVAL * LampBursts.rate(ticks)
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
    val disorientation = SanityPhase.DISORIENTATION.startTick
    val hunt = SanityPhase.HUNT.startTick
    val quiet = meanMinutes(0, random)
    val lateDisorientation = meanMinutes(hunt - 1, random)
    val hunting = meanMinutes(hunt, random)
    val rates = (disorientation until hunt step 200).map(LampBursts::rate)
    check(rates.zipWithNext().all { (a, b) -> b >= a }) { "burst rate must not fall during disorientation" }
    check(LampBursts.rate(disorientation) == LampBursts.rate(0)) { "disorientation must start at the quiet rate" }
    check(quiet in 9.0..11.5) { "quiet interval %.2f min is off the ~10 min target".format(quiet) }
    check(lateDisorientation in 3.5..4.5) { "late disorientation interval %.2f min is off the ~4 min target".format(lateDisorientation) }
    check(hunting in 2.0..2.5) { "hunt interval %.2f min is off the ~2.25 min target".format(hunting) }
    println("lamp bursts: quiet %.2f min, end of disorientation %.2f min, hunt %.2f min".format(quiet, lateDisorientation, hunting))
}
