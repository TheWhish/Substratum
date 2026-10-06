package xyz.thewhish.substratum.reality

import xyz.thewhish.substratum.smiler.Spots
import kotlin.random.Random

class Tension<K>(private val random: Random, private val pause: IntRange) {

    class Offer<K>(val kind: K, val cost: Double, val weight: Double, val big: Boolean, val ready: () -> Boolean)

    private var budget = 0.0
    private var wave = false
    private var opening = false
    private var again = false
    private var next = 0L
    private var last: K? = null
    private var bigAt = -BIG_GAP
    private var misses = 0
    private var price = drawPrice()

    fun accrue(gain: Double) {
        budget = minOf(budget + gain, CAP)
    }

    fun due(now: Long): Boolean = now >= next

    fun pick(now: Long, offers: List<Offer<K>>): Offer<K>? {
        val affordable = offers.filter { minOf(it.cost * price, CAP) <= budget }
        if (affordable.isEmpty()) {
            if (wave) rest(now)
            return null
        }
        if (!wave) {
            wave = true
            opening = true
        }
        val ready = affordable.filter { (!it.big || now - bigAt >= BIG_GAP) && it.ready() }
        if (!opening && !again && ready.isNotEmpty() && ready.all { it.kind == last }) {
            rest(now)
            return null
        }
        val small = ready.any { !it.big }
        val weights = ready.map { it.weight * (if (it.kind == last) REPEAT else 1.0) * (if (opening && small && it.big) OPENING_BIG else 1.0) }
        val choice = Spots.pick(ready, weights, random)
        if (choice == null) next = now + RETRY
        return choice
    }

    fun spend(now: Long, offer: Offer<K>) {
        budget -= offer.cost * price
        price = drawPrice()
        again = (opening || offer.kind != last) && random.nextDouble() < REPEAT
        last = offer.kind
        if (offer.big) bigAt = now
        opening = false
        misses = 0
        next = maxOf(next, now + random.nextLong(GAP.first, GAP.last + 1))
    }

    fun miss(now: Long) {
        next = now + RETRY
        if (++misses < GIVE_UP) return
        misses = 0
        next = now + BACK_OFF
    }

    private fun drawPrice(): Double = random.nextDouble(1.0 - PRICE_SPREAD, 1.0 + PRICE_SPREAD)

    private fun rest(now: Long) {
        wave = false
        misses = 0
        next = now + random.nextInt(pause.first, pause.last + 1)
    }

    companion object {
        const val CAP = 2.0
        const val BIG_GAP = 60 * 20L
        const val RETRY = 2 * 20L
        const val GIVE_UP = 8
        const val BACK_OFF = 30 * 20L
        const val REPEAT = 0.3
        const val OPENING_BIG = 0.25
        const val PRICE_SPREAD = 0.5
        val GAP = 30 * 20L..120 * 20L
    }
}
