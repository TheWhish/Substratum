package xyz.thewhish.substratum.network

import dev.architectury.networking.NetworkManager
import dev.architectury.platform.Platform
import dev.architectury.utils.Env
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.phys.Vec3
import xyz.thewhish.substratum.Substratum
import xyz.thewhish.substratum.client.Collapse
import xyz.thewhish.substratum.client.Feed
import xyz.thewhish.substratum.client.Footage
import xyz.thewhish.substratum.level.Footprint
import xyz.thewhish.substratum.level.Run
import java.util.UUID

object FeedSync {
    private const val RELAY_REACH = 8.0

    private class Signal(val live: Boolean) : CustomPacketPayload {
        constructor(buf: FriendlyByteBuf) : this(buf.readBoolean())

        fun write(buf: FriendlyByteBuf) {
            buf.writeBoolean(live)
        }

        override fun type(): CustomPacketPayload.Type<Signal> = SIGNAL
    }

    private class Watch(val target: UUID) : CustomPacketPayload {
        constructor(buf: FriendlyByteBuf) : this(buf.readUUID())

        fun write(buf: FriendlyByteBuf) {
            buf.writeUUID(target)
        }

        override fun type(): CustomPacketPayload.Type<Watch> = WATCH
    }

    private class Fall(val player: UUID, val landing: Footprint.Landing?) : CustomPacketPayload {
        constructor(buf: FriendlyByteBuf) : this(
            buf.readUUID(),
            buf.readNullable { Footprint.Landing(it.readFloat(), Vec3(it.readDouble(), 0.0, it.readDouble())) },
        )

        fun write(buf: FriendlyByteBuf) {
            buf.writeUUID(player)
            buf.writeNullable(landing) { out, landing ->
                out.writeFloat(landing.yaw)
                out.writeDouble(landing.shift.x)
                out.writeDouble(landing.shift.z)
            }
        }

        override fun type(): CustomPacketPayload.Type<Fall> = FALL
    }

    private class Tape(val born: Long, val level: Int) : CustomPacketPayload {
        constructor(buf: FriendlyByteBuf) : this(buf.readLong(), buf.readVarInt())

        fun write(buf: FriendlyByteBuf) {
            buf.writeLong(born)
            buf.writeVarInt(level)
        }

        override fun type(): CustomPacketPayload.Type<Tape> = TAPE
    }

    private class Frame(
        private val kind: CustomPacketPayload.Type<Frame>,
        val id: Int,
        val tick: Int,
        val x: Double,
        val y: Double,
        val z: Double,
        val yaw: Float,
        val pitch: Float,
    ) : CustomPacketPayload {
        constructor(kind: CustomPacketPayload.Type<Frame>, buf: FriendlyByteBuf) :
            this(kind, buf.readVarInt(), buf.readVarInt(), buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readFloat(), buf.readFloat())

        fun write(buf: FriendlyByteBuf) {
            buf.writeVarInt(id)
            buf.writeVarInt(tick)
            buf.writeDouble(x)
            buf.writeDouble(y)
            buf.writeDouble(z)
            buf.writeFloat(yaw)
            buf.writeFloat(pitch)
        }

        fun valid(): Boolean = x.isFinite() && y.isFinite() && z.isFinite() && yaw.isFinite() && pitch.isFinite()

        override fun type(): CustomPacketPayload.Type<Frame> = kind
    }

    private val SIGNAL = CustomPacketPayload.Type<Signal>(Substratum.id("signal"))
    private val SIGNAL_CODEC: StreamCodec<FriendlyByteBuf, Signal> = CustomPacketPayload.codec(Signal::write, ::Signal)
    private val WATCH = CustomPacketPayload.Type<Watch>(Substratum.id("watch"))
    private val WATCH_CODEC: StreamCodec<FriendlyByteBuf, Watch> = CustomPacketPayload.codec(Watch::write, ::Watch)
    private val FALL = CustomPacketPayload.Type<Fall>(Substratum.id("fall"))
    private val FALL_CODEC: StreamCodec<FriendlyByteBuf, Fall> = CustomPacketPayload.codec(Fall::write, ::Fall)
    private val TAPE = CustomPacketPayload.Type<Tape>(Substratum.id("tape"))
    private val TAPE_CODEC: StreamCodec<FriendlyByteBuf, Tape> = CustomPacketPayload.codec(Tape::write, ::Tape)
    private val FRAME_UP = CustomPacketPayload.Type<Frame>(Substratum.id("frame_up"))
    private val FRAME_UP_CODEC: StreamCodec<FriendlyByteBuf, Frame> = CustomPacketPayload.codec(Frame::write) { Frame(FRAME_UP, it) }
    private val FRAME = CustomPacketPayload.Type<Frame>(Substratum.id("frame"))
    private val FRAME_CODEC: StreamCodec<FriendlyByteBuf, Frame> = CustomPacketPayload.codec(Frame::write) { Frame(FRAME, it) }

    fun register() {
        NetworkManager.registerReceiver(NetworkManager.Side.C2S, WATCH, WATCH_CODEC) { watch, context ->
            context.queue { (context.player as? ServerPlayer)?.let { Run.watch(it, watch.target) } }
        }
        NetworkManager.registerReceiver(NetworkManager.Side.C2S, FRAME_UP, FRAME_UP_CODEC) { frame, context ->
            context.queue { (context.player as? ServerPlayer)?.let { relay(it, frame) } }
        }
        if (Platform.getEnvironment() == Env.SERVER) {
            NetworkManager.registerS2CPayloadType(SIGNAL, SIGNAL_CODEC)
            NetworkManager.registerS2CPayloadType(FALL, FALL_CODEC)
            NetworkManager.registerS2CPayloadType(TAPE, TAPE_CODEC)
            NetworkManager.registerS2CPayloadType(FRAME, FRAME_CODEC)
            return
        }
        NetworkManager.registerReceiver(NetworkManager.Side.S2C, SIGNAL, SIGNAL_CODEC) { signal, context ->
            context.queue { Feed.signal(signal.live) }
        }
        NetworkManager.registerReceiver(NetworkManager.Side.S2C, FALL, FALL_CODEC) { fall, context ->
            context.queue { Collapse.follow(fall.player, fall.landing) }
        }
        NetworkManager.registerReceiver(NetworkManager.Side.S2C, TAPE, TAPE_CODEC) { tape, context ->
            context.queue { Feed.tape(tape.born, tape.level) }
        }
        NetworkManager.registerReceiver(NetworkManager.Side.S2C, FRAME, FRAME_CODEC) { frame, context ->
            context.queue { Footage.receive(frame.id, frame.tick, frame.x, frame.y, frame.z, frame.yaw, frame.pitch) }
        }
    }

    fun signal(player: ServerPlayer, live: Boolean) {
        NetworkManager.sendToPlayer(player, Signal(live))
    }

    fun tape(player: ServerPlayer, born: Long, level: Int) {
        NetworkManager.sendToPlayer(player, Tape(born, level))
    }

    private fun relay(player: ServerPlayer, frame: Frame) {
        if (!frame.valid() || player.position().distanceToSqr(frame.x, frame.y, frame.z) > RELAY_REACH * RELAY_REACH) return
        val shown = Frame(FRAME, player.id, frame.tick, frame.x, frame.y, frame.z, frame.yaw, frame.pitch)
        for (watcher in player.server.playerList.players) {
            if (watcher !== player && watcher.camera === player) NetworkManager.sendToPlayer(watcher, shown)
        }
    }

    fun frame(tick: Int, x: Double, y: Double, z: Double, yaw: Float, pitch: Float) {
        NetworkManager.sendToServer(Frame(FRAME_UP, 0, tick, x, y, z, yaw, pitch))
    }

    fun fall(server: MinecraftServer, player: UUID, landing: Footprint.Landing) {
        NetworkManager.sendToPlayers(server.playerList.players, Fall(player, landing))
    }

    fun rise(server: MinecraftServer, player: UUID) {
        NetworkManager.sendToPlayers(server.playerList.players, Fall(player, null))
    }

    fun watch(target: UUID) {
        NetworkManager.sendToServer(Watch(target))
    }
}
