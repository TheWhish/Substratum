package xyz.thewhish.substratum.network

import dev.architectury.event.events.common.PlayerEvent
import dev.architectury.networking.NetworkManager
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.server.level.ServerPlayer
import xyz.thewhish.substratum.Substratum
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.atan
import kotlin.math.tan

object ViewportSync {

    enum class Perspective { FIRST_PERSON, THIRD_PERSON_BACK, THIRD_PERSON_FRONT }

    data class Viewport(val vFovDegrees: Float, val aspect: Float, val perspective: Perspective) {
        val halfVFov: Double get() = Math.toRadians(vFovDegrees / 2.0)

        val halfHFov: Double get() = atan(tan(halfVFov) * aspect)
    }

    val DEFAULT = Viewport(70f, 16f / 9f, Perspective.FIRST_PERSON)

    private const val MIN_FOV = 30f
    private const val MAX_FOV = 110f
    private const val MIN_ASPECT = 0.25f
    private const val MAX_ASPECT = 8f

    private class Report(val vFovDegrees: Float, val aspect: Float, val perspective: Perspective) : CustomPacketPayload {
        constructor(buf: FriendlyByteBuf) : this(buf.readFloat(), buf.readFloat(), buf.readEnum(Perspective::class.java))

        fun write(buf: FriendlyByteBuf) {
            buf.writeFloat(vFovDegrees)
            buf.writeFloat(aspect)
            buf.writeEnum(perspective)
        }

        override fun type(): CustomPacketPayload.Type<Report> = TYPE
    }

    private val TYPE = CustomPacketPayload.Type<Report>(Substratum.id("viewport"))
    private val CODEC: StreamCodec<FriendlyByteBuf, Report> = CustomPacketPayload.codec(Report::write, ::Report)

    private val reported = ConcurrentHashMap<UUID, Viewport>()

    fun register() {
        NetworkManager.registerReceiver(NetworkManager.Side.C2S, TYPE, CODEC, ::accept)
        PlayerEvent.PLAYER_QUIT.register { reported.remove(it.uuid) }
    }

    fun of(player: ServerPlayer): Viewport = reported[player.uuid] ?: DEFAULT

    fun report(vFovDegrees: Float, aspect: Float, perspective: Perspective) {
        NetworkManager.sendToServer(Report(vFovDegrees, aspect, perspective))
    }

    private fun accept(report: Report, context: NetworkManager.PacketContext) {
        val player = context.player as? ServerPlayer ?: return
        if (!report.vFovDegrees.isFinite() || !report.aspect.isFinite()) return
        reported[player.uuid] = Viewport(
            report.vFovDegrees.coerceIn(MIN_FOV, MAX_FOV),
            report.aspect.coerceIn(MIN_ASPECT, MAX_ASPECT),
            report.perspective
        )
    }
}
