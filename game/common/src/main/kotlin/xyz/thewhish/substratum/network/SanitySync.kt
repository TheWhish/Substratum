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

object SanitySync {

    private class Update(val ticks: Int) : CustomPacketPayload {
        constructor(buf: FriendlyByteBuf) : this(buf.readVarInt())

        fun write(buf: FriendlyByteBuf) {
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
            if (update.ticks < 0) return@registerReceiver
            context.queue { SanityClient.accept(update.ticks) }
        }
    }

    fun send(player: ServerPlayer, ticks: Int) {
        NetworkManager.sendToPlayer(player, Update(ticks))
    }
}
