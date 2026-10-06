package xyz.thewhish.substratum.network

import dev.architectury.networking.NetworkManager
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.server.level.ServerPlayer
import xyz.thewhish.substratum.Substratum
import xyz.thewhish.substratum.smiler.SmilerDirector

object SmilerSync {

    private class Gone(val entity: Int) : CustomPacketPayload {
        constructor(buf: FriendlyByteBuf) : this(buf.readVarInt())

        fun write(buf: FriendlyByteBuf) {
            buf.writeVarInt(entity)
        }

        override fun type(): CustomPacketPayload.Type<Gone> = TYPE
    }

    private val TYPE = CustomPacketPayload.Type<Gone>(Substratum.id("smiler_gone"))
    private val CODEC: StreamCodec<FriendlyByteBuf, Gone> = CustomPacketPayload.codec(Gone::write, ::Gone)

    fun register() {
        NetworkManager.registerReceiver(NetworkManager.Side.C2S, TYPE, CODEC) { gone, context ->
            val player = context.player as? ServerPlayer ?: return@registerReceiver
            context.queue { SmilerDirector.lost(player, gone.entity) }
        }
    }

    fun gone(entity: Int) {
        NetworkManager.sendToServer(Gone(entity))
    }
}
