package xyz.thewhish.substratum.reality

import xyz.thewhish.substratum.sanity.SanityPhase
import kotlin.random.Random

private const val STEP = Director.STEP.toLong()
private const val MINUTE = 1200L
private const val TWO_HOURS = 120 * MINUTE
private const val RUNS = 40
private val PAUSE = 120 * 20..480 * 20

private class Mechanic(val mark: Char, val cost: Double, val big: Boolean, val from: Double, val success: Double, val busy: LongRange)

private class Act(val at: Long, val mechanic: Mechanic, val opening: Boolean)

private val SMILER = Mechanic('S', Manifestation.SMILER.cost, Manifestation.SMILER.big, Manifestation.SMILER.pressure, 0.4, 10 * 20L..25 * 20L)
private val WHISPER = Mechanic('w', 0.25, false, 0.25, 0.8, 3 * 20L..6 * 20L)

private fun simulate(mechanics: List<Mechanic>, duration: Long, random: Random, pressure: (Long) -> Double): List<Act> {
    val tension = Tension<Mechanic>(random, PAUSE)
    val busyUntil = HashMap<Mechanic, Long>()
    val acts = ArrayList<Act>()
    var quietSince = 0L
    var now = 0L
    while (now < duration) {
        val p = pressure(now)
        tension.accrue(Director.gain(p))
        if (tension.due(now)) {
            val offers = mechanics.filter { p >= it.from }.map { m -> Tension.Offer(m, m.cost, 1.0, m.big) { now >= (busyUntil[m] ?: 0L) } }
            val offer = tension.pick(now, offers)
            if (offer != null && random.nextDouble() < offer.kind.success) {
                tension.spend(now, offer)
                acts += Act(now, offer.kind, now - quietSince >= PAUSE.first)
                busyUntil[offer.kind] = now + random.nextLong(offer.kind.busy.first, offer.kind.busy.last + 1)
                quietSince = now
            } else if (offer != null) {
                tension.miss(now)
            }
        }
        now += STEP
    }
    return acts
}

private fun clock(ticks: Long): String = "%d:%02d".format(ticks / MINUTE, ticks % MINUTE / 20)

private fun tape(acts: List<Act>): String = acts.joinToString(" ") { "${clock(it.at)}${it.mechanic.mark}" }

private fun gaps(acts: List<Act>, duration: Long): List<Long> = (listOf(0L) + acts.map { it.at } + duration).zipWithNext { a, b -> b - a }

private fun crowd(acts: List<Act>, window: Long): Int = acts.indices.maxOfOrNull { i -> acts.count { it.at >= acts[i].at && it.at < acts[i].at + window } } ?: 0

fun main() {
    val random = Random(11)
    val means = HashMap<Double, Double>()
    for (p in listOf(0.25, 0.5, 1.0)) {
        val runs = List(RUNS) { simulate(listOf(SMILER), TWO_HOURS, random) { p } }
        for (acts in runs) {
            check(acts.zipWithNext().all { (a, b) -> b.at - a.at >= Tension.BIG_GAP }) { "two big ones closer than 60 s at p $p" }
            check(crowd(acts, 5 * MINUTE) <= 3) { "more than 3 smilers in 5 minutes at p $p: ${tape(acts)}" }
        }
        val mean = runs.sumOf { it.size }.let { TWO_HOURS.toDouble() / MINUTE * RUNS / it }
        means[p] = mean
        val longest = runs.map { gaps(it, TWO_HOURS).max() / MINUTE.toDouble() }
        println("p %.2f: a smiler every %.1f min, longest silence %.1f..%.1f min | %s".format(p, mean, longest.min(), longest.max(), tape(runs[0])))
        if (p == 1.0) {
            check(mean in 3.0..6.0) { "smiler every %.1f min at full pressure is off the 3-6 min target".format(mean) }
            check(longest.all { it in 6.0..14.0 }) { "silences at full pressure must reach 6 min and stay under 14: $longest" }
        }
    }
    check(means.getValue(0.25) > means.getValue(0.5) && means.getValue(0.5) > means.getValue(1.0)) { "smilers must come more often as pressure grows: $means" }

    val firsts = List(RUNS * 5) {
        simulate(listOf(SMILER), 20 * MINUTE, random) { SanityPhase.pressure(it.toDouble()) }.firstOrNull()?.at ?: (20 * MINUTE)
    }.map { it / MINUTE.toDouble() }
    check(firsts.min() >= 5.75) { "a smiler came before pressure reached its threshold" }
    check(firsts.average() in 7.5..10.5) { "first smiler at %.1f min on average is off the 7.5-10.5 target".format(firsts.average()) }
    println("first smiler after entering: %.1f..%.1f min, %.1f on average".format(firsts.min(), firsts.max(), firsts.average()))

    val mixed = List(RUNS) { simulate(listOf(SMILER, WHISPER), TWO_HOURS, random) { 1.0 } }
    for (acts in mixed) {
        val bigs = acts.filter { it.mechanic.big }
        check(bigs.zipWithNext().all { (a, b) -> b.at - a.at >= Tension.BIG_GAP }) { "two big ones closer than 60 s: ${tape(acts)}" }
    }
    val all = mixed.flatten()
    val openings = all.filter { it.opening }
    val smallFirst = openings.count { !it.mechanic.big }.toDouble() / openings.size
    val repeats = mixed.sumOf { acts -> acts.zipWithNext().count { (a, b) -> a.mechanic == b.mechanic } }.toDouble() / mixed.sumOf { it.size - 1 }
    val smilerShare = all.count { it.mechanic == SMILER }.toDouble() / all.size
    check(smallFirst >= 0.6) { "after a long silence the first one must usually be small, got %.2f".format(smallFirst) }
    check(smilerShare in 0.1..0.6) { "smiler share %.2f among mixed happenings is off".format(smilerShare) }
    println("mixed: smiler %.0f%%, small first after silence %.0f%%, same kind twice in a row %.0f%% | %s".format(smilerShare * 100, smallFirst * 100, repeats * 100, tape(mixed[0])))
}
