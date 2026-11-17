package xyz.thewhish.substratum.reality

enum class Manifestation(val cost: Double, val big: Boolean, val pressure: Double) {
    SMILER(0.85, true, 0.15);

    val key: String = name.lowercase()
}
