package xyz.thewhish.substratum.network

import dev.architectury.networking.NetworkManager
import dev.architectury.platform.Platform
import dev.architectury.utils.Env
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.server.level.ServerPlayer
import xyz.thewhish.substratum.Substratum
import xyz.thewhish.substratum.client.SanityClient
import xyz.thewhish.substratum.sanity.SanityPhase

object SanitySync {

    private class Update(val phase: Int, val ticks: Int) : CustomPacketPayload {
        constructor(buf: FriendlyByteBuf) : this(buf.readByte().toInt(), buf.readVarInt())

        fun write(buf: FriendlyByteBuf) {
            buf.writeByte(phase)
            buf.writeVarInt(ticks)
        }

        override fun type(): CustomPacketPayload.Type<Update> = TYPE
    }

    private val TYPE = CustomPacketPayload.Type<Update>(Substratum.id("sanity"))
    private val CODEC: StreamCodec<FriendlyByteBuf, Update> = CustomPacketPayload.codec(Update::write, ::Update)

    fun register() {
        if (Platform.getEnvironment() == Env.SERVER) {
            NetworkManager.registerS2CPayloadType(TYPE, CODEC)
            return
        }
        NetworkManager.registerReceiver(NetworkManager.Side.S2C, TYPE, CODEC) { update, context ->
            val phase = SanityPhase.entries.getOrNull(update.phase) ?: return@registerReceiver
            if (update.ticks < 0) return@registerReceiver
            context.queue { SanityClient.accept(phase, update.ticks) }
        }
    }

    fun send(player: ServerPlayer, ticks: Int) {
        NetworkManager.sendToPlayer(player, Update(SanityPhase.of(ticks).ordinal, ticks))
    }
}
