package xyz.thewhish.substratum.network

import dev.architectury.networking.NetworkManager
import dev.architectury.platform.Platform
import dev.architectury.utils.Env
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.server.level.ServerPlayer
import xyz.thewhish.substratum.Substratum
import xyz.thewhish.substratum.client.Feed
import xyz.thewhish.substratum.level.Run

object FeedSync {

    private class Signal(val live: Boolean) : CustomPacketPayload {
        constructor(buf: FriendlyByteBuf) : this(buf.readBoolean())

        fun write(buf: FriendlyByteBuf) {
            buf.writeBoolean(live)
        }

        override fun type(): CustomPacketPayload.Type<Signal> = SIGNAL
    }

    private class Switch(val forward: Boolean) : CustomPacketPayload {
        constructor(buf: FriendlyByteBuf) : this(buf.readBoolean())

        fun write(buf: FriendlyByteBuf) {
            buf.writeBoolean(forward)
        }

        override fun type(): CustomPacketPayload.Type<Switch> = SWITCH
    }

    private val SIGNAL = CustomPacketPayload.Type<Signal>(Substratum.id("signal"))
    private val SIGNAL_CODEC: StreamCodec<FriendlyByteBuf, Signal> = CustomPacketPayload.codec(Signal::write, ::Signal)
    private val SWITCH = CustomPacketPayload.Type<Switch>(Substratum.id("switch"))
    private val SWITCH_CODEC: StreamCodec<FriendlyByteBuf, Switch> = CustomPacketPayload.codec(Switch::write, ::Switch)

    fun register() {
        NetworkManager.registerReceiver(NetworkManager.Side.C2S, SWITCH, SWITCH_CODEC) { switch, context ->
            context.queue { (context.player as? ServerPlayer)?.let { Run.turn(it, switch.forward) } }
        }
        if (Platform.getEnvironment() == Env.SERVER) {
            NetworkManager.registerS2CPayloadType(SIGNAL, SIGNAL_CODEC)
            return
        }
        NetworkManager.registerReceiver(NetworkManager.Side.S2C, SIGNAL, SIGNAL_CODEC) { signal, context ->
            context.queue { Feed.signal(signal.live) }
        }
    }

    fun signal(player: ServerPlayer, live: Boolean) {
        NetworkManager.sendToPlayer(player, Signal(live))
    }

    fun switch(forward: Boolean) {
        NetworkManager.sendToServer(Switch(forward))
    }
}
