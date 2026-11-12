package xyz.thewhish.substratum.client

import net.minecraft.client.Minecraft
import net.minecraft.util.Mth
import net.minecraft.world.entity.player.Player
import xyz.thewhish.substratum.network.FeedSync
import kotlin.math.abs

object Footage {
    private const val DELAY = 3.0
    private const val RESYNC = 10.0
    private const val CATCH_UP = 0.05
    private const val KEEP = 40

    internal class Frame(val tick: Int, val x: Double, val y: Double, val z: Double, val yaw: Float, val pitch: Float)

    private val frames = ArrayDeque<Frame>()
    private var source = -1
    private var clock = Double.NaN
    private var count = 0
    private var sent: Frame? = null

    fun receive(id: Int, tick: Int, x: Double, y: Double, z: Double, yaw: Float, pitch: Float) {
        if (id != source || frames.lastOrNull()?.let { tick <= it.tick } == true) clear(id)
        val previous = frames.lastOrNull()
        frames.addLast(Frame(tick, x, y, z, previous?.let { it.yaw + Mth.wrapDegrees(yaw - it.yaw) } ?: yaw, pitch))
        if (frames.size > KEEP) frames.removeFirst()
    }

    fun reset() {
        clear(-1)
        sent = null
    }

    private fun clear(id: Int) {
        frames.clear()
        source = id
        clock = Double.NaN
    }

    fun tick(minecraft: Minecraft) {
        send(minecraft)
        val camera = minecraft.cameraEntity as? Player ?: return
        if (camera === minecraft.player || camera.id != source) return
        play()?.let { show(camera, it) }
    }

    private fun send(minecraft: Minecraft) {
        count++
        val player = minecraft.player ?: return
        if (player.isSpectator) return
        val last = sent
        if (last != null && last.x == player.x && last.y == player.y && last.z == player.z && last.yaw == player.yRot && last.pitch == player.xRot) return
        if (last != null && last.tick < count - 1) FeedSync.frame(count - 1, last.x, last.y, last.z, last.yaw, last.pitch)
        sent = Frame(count, player.x, player.y, player.z, player.yRot, player.xRot)
        FeedSync.frame(count, player.x, player.y, player.z, player.yRot, player.xRot)
    }

    internal fun play(): Frame? {
        if (frames.isEmpty()) return null
        val target = frames.last().tick - DELAY
        clock = if (clock.isNaN() || abs(target - clock) > RESYNC) target else clock + 1.0 + (target - clock) * CATCH_UP
        return sample(clock)
    }

    private fun sample(at: Double): Frame {
        val after = frames.indexOfFirst { it.tick >= at }
        if (after < 0) return frames.last()
        val to = frames[after]
        val from = if (after == 0) to else frames[after - 1]
        val t = if (to.tick == from.tick) 1.0 else (at - from.tick) / (to.tick - from.tick)
        return Frame(to.tick, Mth.lerp(t, from.x, to.x), Mth.lerp(t, from.y, to.y), Mth.lerp(t, from.z, to.z), Mth.lerp(t, from.yaw.toDouble(), to.yaw.toDouble()).toFloat(), Mth.lerp(t, from.pitch.toDouble(), to.pitch.toDouble()).toFloat())
    }

    private fun show(player: Player, frame: Frame) {
        player.setPos(frame.x, frame.y, frame.z)
        player.yRot += Mth.wrapDegrees(frame.yaw - player.yRot)
        player.xRot = frame.pitch
        player.yHeadRot = player.yRot
    }
}
