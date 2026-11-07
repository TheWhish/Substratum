package xyz.thewhish.substratum.client

import dev.architectury.platform.Platform

object Launcher {
    val skin: String? = System.getProperty("substratum.skin")

    fun require() {
        val linked = Link.start()
        check(Platform.isDevelopmentEnvironment() || (skin != null && linked)) {
            "Substratum starts only from the Substratum launcher"
        }
    }
}
