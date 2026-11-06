package xyz.thewhish.substratum.client

import kotlin.math.abs

private fun near(a: Float, b: Float) = abs(a - b) < 1e-5f

fun main() {
    check(near(Atmosphere.approach(0f, 1f, 10f), 0.5f)) { "entering fades in over 20 ticks of game time" }
    check(near(Atmosphere.approach(0f, 1f, 400f), 1f)) { "entering settles at full strength" }
    check(near(Atmosphere.approach(1f, 0f, 20f), 0.5f)) { "leaving fades out over 40 ticks of game time" }
    check(near(Atmosphere.approach(1f, 0f, 400f), 0f)) { "leaving settles at zero" }
    check(near(Atmosphere.approach(0.3f, 1f, 0f), 0.3f)) { "frozen game time holds the fade" }
    check(near(Atmosphere.approach(0.3f, 1f, -50f), 0.3f)) { "game time jumping back never rewinds the fade" }
    check(near(Atmosphere.approach(1f, 1f, 0f), 1f)) { "settled presence needs no ticks" }
    println("presence: fades follow game time, frozen time holds them")
}
