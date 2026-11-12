package xyz.thewhish.substratum.client

import kotlin.math.abs
import kotlin.random.Random

fun main() {
    val random = Random(7)
    val arrival = IntArray(401)
    for (frame in 1..400) arrival[frame] = maxOf(arrival[frame - 1], frame + random.nextInt(0, 3))
    var next = 1
    var previous = Float.NaN
    var worst = 0f
    for (tick in 1..400) {
        while (next <= 400 && arrival[next] <= tick) {
            Footage.receive(1, next, next * 0.2, 64.0, 0.0, next * 1.5f, 0f)
            next++
        }
        val frame = Footage.play() ?: continue
        if (tick > 40 && !previous.isNaN()) worst = maxOf(worst, abs(frame.yaw - previous - 1.5f))
        previous = frame.yaw
    }
    check(worst < 0.3f) { "playback stutters by $worst deg per tick" }
    println("footage: frames late by up to two ticks play back within ${"%.2f".format(worst)} deg of a steady turn")
}
