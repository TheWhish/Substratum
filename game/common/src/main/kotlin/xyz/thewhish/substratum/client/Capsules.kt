package xyz.thewhish.substratum.client

import net.minecraft.client.Minecraft
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import xyz.thewhish.substratum.level.SubstratumLevels

object Capsules {

    const val MAX = 8
    private const val REACH = 32.0
    private const val THICKNESS = 0.9f

    class Capsule {
        var x = 0.0
        var z = 0.0
        var bottom = 0.0
        var top = 0.0
        var radius = 0f
    }

    val capsules = Array(MAX) { Capsule() }
    var count = 0
        private set

    fun update(minecraft: Minecraft, partialTick: Float) {
        count = 0
        val level = minecraft.level?.takeIf { it.dimension() == SubstratumLevels.LEVEL_0 } ?: return
        val camera = minecraft.gameRenderer.mainCamera.position
        level.entitiesForRendering()
            .filterIsInstance<LivingEntity>()
            .filter { it.isAlive && !it.isInvisible && !it.isSpectator && it.distanceToSqr(camera) < REACH * REACH }
            .sortedWith(compareBy({ it !is Player }, { it.distanceToSqr(camera) }))
            .take(MAX)
            .forEach { fill(capsules[count++], it, partialTick) }
    }

    inline fun write(x: Double, y: Double, z: Double, put: (Float) -> Unit) {
        for (capsule in capsules) {
            put((capsule.x - x).toFloat())
            put((capsule.bottom - y).toFloat())
            put((capsule.z - z).toFloat())
            put(capsule.radius)
            put((capsule.x - x).toFloat())
            put((capsule.top - y).toFloat())
            put((capsule.z - z).toFloat())
            put(0f)
        }
    }

    private fun fill(capsule: Capsule, entity: LivingEntity, partialTick: Float) {
        val position = entity.getPosition(partialTick)
        val radius = entity.bbWidth * 0.5f * THICKNESS
        capsule.x = position.x
        capsule.z = position.z
        capsule.bottom = position.y + radius
        capsule.top = position.y + maxOf(entity.bbHeight - radius, radius)
        capsule.radius = radius
    }
}
