package xyz.thewhish.substratum.client

import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import xyz.thewhish.substratum.Substratum

object Lettering {
    private const val SHARPEST = 12

    fun text(component: Component, magnification: Float = 1f): MutableComponent {
        val pixels = (Minecraft.getInstance().window.guiScale * magnification).toInt().coerceIn(1, SHARPEST)
        return component.copy().withStyle { it.withFont(Substratum.id("text/$pixels")) }
    }
}
