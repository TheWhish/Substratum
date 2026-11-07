package xyz.thewhish.substratum.client

import dev.architectury.platform.Platform

object Launcher {
    val skin: String? = System.getProperty("substratum.skin")

    fun require() {
        check(skin != null || Platform.isDevelopmentEnvironment()) {
            "Substratum starts only from the Substratum launcher"
        }
    }
}
