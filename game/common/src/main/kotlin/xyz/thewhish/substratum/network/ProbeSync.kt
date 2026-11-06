package xyz.thewhish.substratum.network

import dev.architectury.networking.NetworkManager
import dev.architectury.platform.Platform
import dev.architectury.utils.Env
import net.minecraft.core.BlockPos
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.phys.Vec3
import xyz.thewhish.substratum.Substratum
import xyz.thewhish.substratum.client.ProbeView
import xyz.thewhish.substratum.gaze.Gaze
import xyz.thewhish.substratum.gaze.Probe
import xyz.thewhish.substratum.gaze.Viewer

object ProbeSync {

    class Frame(
        val active: Boolean,
        val frozen: Boolean,
        val kind: Viewer.Kind,
        val perspective: ViewportSync.Perspective,
        val eye: Vec3,
        val forward: Vec3,
        val halfH: Float,
        val halfV: Float,
        val margin: Float,
        val range: Float,
        val fov: Float,
        val latency: Int,
        val micros: Int,
        val windows: Int,
        val origin: BlockPos,
        val points: ByteArray,
        val soon: List<Vec3>,
    ) : CustomPacketPayload {

        fun write(buf: FriendlyByteBuf) {
            buf.writeBoolean(active)
            if (!active) return
            buf.writeBoolean(frozen)
            buf.writeEnum(kind)
            buf.writeEnum(perspective)
            buf.writeVec3(eye)
            buf.writeVec3(forward)
            buf.writeFloat(halfH)
            buf.writeFloat(halfV)
            buf.writeFloat(margin)
            buf.writeFloat(range)
            buf.writeFloat(fov)
            buf.writeVarInt(latency)
            buf.writeVarInt(micros)
            buf.writeVarInt(windows)
            buf.writeBlockPos(origin)
            buf.writeByteArray(points)
            buf.writeVarInt(soon.size)
            soon.forEach(buf::writeVec3)
        }

        override fun type(): CustomPacketPayload.Type<Frame> = TYPE
    }

    private val OFF = Frame(false, false, Viewer.Kind.LOOKING, ViewportSync.Perspective.FIRST_PERSON, Vec3.ZERO, Vec3.ZERO, 0f, 0f, 0f, 0f, 0f, 0, 0, 0, BlockPos.ZERO, ByteArray(0), emptyList())
    private val TYPE = CustomPacketPayload.Type<Frame>(Substratum.id("gaze_probe"))
    private val CODEC: StreamCodec<FriendlyByteBuf, Frame> = CustomPacketPayload.codec(Frame::write, ::read)

    fun register() {
        if (Platform.getEnvironment() == Env.SERVER) {
            NetworkManager.registerS2CPayloadType(TYPE, CODEC)
            return
        }
        NetworkManager.registerReceiver(NetworkManager.Side.S2C, TYPE, CODEC) { frame, context ->
            if (!valid(frame)) {
                Substratum.LOGGER.warn("Ignoring a malformed gaze probe frame: {} point bytes, {} soon points", frame.points.size, frame.soon.size)
                return@registerReceiver
            }
            context.queue { ProbeView.accept(frame) }
        }
    }

    fun send(player: ServerPlayer, frame: Frame) {
        NetworkManager.sendToPlayer(player, frame)
    }

    fun off(player: ServerPlayer) {
        NetworkManager.sendToPlayer(player, OFF)
    }

    private fun read(buf: FriendlyByteBuf): Frame {
        if (!buf.readBoolean()) return OFF
        return Frame(
            active = true,
            frozen = buf.readBoolean(),
            kind = buf.readEnum(Viewer.Kind::class.java),
            perspective = buf.readEnum(ViewportSync.Perspective::class.java),
            eye = buf.readVec3(),
            forward = buf.readVec3(),
            halfH = buf.readFloat(),
            halfV = buf.readFloat(),
            margin = buf.readFloat(),
            range = buf.readFloat(),
            fov = buf.readFloat(),
            latency = buf.readVarInt(),
            micros = buf.readVarInt(),
            windows = buf.readVarInt(),
            origin = buf.readBlockPos(),
            points = buf.readByteArray(Probe.MAX_POINTS * 4),
            soon = List(buf.readVarInt().also { require(it in 0..Probe.MAX_SOON) { "too many soon points: $it" } }) { buf.readVec3() },
        )
    }

    private fun valid(frame: Frame): Boolean {
        if (!frame.active) return true
        val numbers = listOf(frame.halfH, frame.halfV, frame.margin, frame.range, frame.fov)
        if (numbers.any { !it.isFinite() } || frame.points.size % 4 != 0) return false
        val sights = Gaze.Sight.entries.size
        for (i in frame.points.indices step 4) {
            if (frame.points[i] !in -Probe.REACH..Probe.REACH || frame.points[i + 2] !in -Probe.REACH..Probe.REACH) return false
            if (frame.points[i + 1] !in -Probe.RISE..Probe.RISE || frame.points[i + 3] !in 0 until sights) return false
        }
        return true
    }
}
