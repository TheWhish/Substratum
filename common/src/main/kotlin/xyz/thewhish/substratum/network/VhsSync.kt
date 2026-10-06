package xyz.thewhish.substratum.network

import dev.architectury.networking.NetworkManager
import dev.architectury.platform.Platform
import dev.architectury.utils.Env
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.server.level.ServerPlayer
import xyz.thewhish.substratum.Substratum
import xyz.thewhish.substratum.client.Vhs

object VhsSync {

    const val TICKS = 5

    private object Glitch : CustomPacketPayload {
        override fun type(): CustomPacketPayload.Type<Glitch> = TYPE
    }

    private val TYPE = CustomPacketPayload.Type<Glitch>(Substratum.id("vhs"))
    private val CODEC: StreamCodec<FriendlyByteBuf, Glitch> = StreamCodec.unit(Glitch)

    fun register() {
        if (Platform.getEnvironment() == Env.SERVER) {
            NetworkManager.registerS2CPayloadType(TYPE, CODEC)
            return
        }
        NetworkManager.registerReceiver(NetworkManager.Side.S2C, TYPE, CODEC) { _, context -> context.queue { Vhs.burst() } }
    }

    fun send(player: ServerPlayer) {
        NetworkManager.sendToPlayer(player, Glitch)
    }
}
