package xyz.thewhish.substratum.network

import dev.architectury.networking.NetworkManager
import dev.architectury.platform.Platform
import dev.architectury.utils.Env
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.server.level.ServerPlayer
import xyz.thewhish.substratum.Substratum
import xyz.thewhish.substratum.client.Blackout
import xyz.thewhish.substratum.level.Blackouts

object BlackoutSync {

    private class Start(val victim: Int, val start: Long, val seed: Long) : CustomPacketPayload {
        constructor(buf: FriendlyByteBuf) : this(buf.readVarInt(), buf.readLong(), buf.readLong())

        fun write(buf: FriendlyByteBuf) {
            buf.writeVarInt(victim)
            buf.writeLong(start)
            buf.writeLong(seed)
        }

        override fun type(): CustomPacketPayload.Type<Start> = TYPE
    }

    private val TYPE = CustomPacketPayload.Type<Start>(Substratum.id("blackout"))
    private val CODEC: StreamCodec<FriendlyByteBuf, Start> = CustomPacketPayload.codec(Start::write, ::Start)

    fun register() {
        if (Platform.getEnvironment() == Env.SERVER) {
            NetworkManager.registerS2CPayloadType(TYPE, CODEC)
            return
        }
        NetworkManager.registerReceiver(NetworkManager.Side.S2C, TYPE, CODEC) { start, context ->
            context.queue { Blackout.accept(Blackouts.Event(start.victim, start.start, start.seed)) }
        }
    }

    fun send(player: ServerPlayer, event: Blackouts.Event) {
        NetworkManager.sendToPlayer(player, Start(event.victim, event.start, event.seed))
    }
}
