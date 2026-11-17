package xyz.thewhish.substratum.network

import dev.architectury.networking.NetworkManager
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.phys.Vec3
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

    private class Spotted(val entity: Int, val eye: Vec3) : CustomPacketPayload {
        constructor(buf: FriendlyByteBuf) : this(buf.readVarInt(), buf.readVec3())

        fun write(buf: FriendlyByteBuf) {
            buf.writeVarInt(entity)
            buf.writeVec3(eye)
        }

        override fun type(): CustomPacketPayload.Type<Spotted> = SPOTTED

        fun valid(): Boolean = eye.x.isFinite() && eye.y.isFinite() && eye.z.isFinite()
    }

    private class Struck(val entity: Int) : CustomPacketPayload {
        constructor(buf: FriendlyByteBuf) : this(buf.readVarInt())

        fun write(buf: FriendlyByteBuf) {
            buf.writeVarInt(entity)
        }

        override fun type(): CustomPacketPayload.Type<Struck> = STRUCK
    }

    private val TYPE = CustomPacketPayload.Type<Gone>(Substratum.id("smiler_gone"))
    private val CODEC: StreamCodec<FriendlyByteBuf, Gone> = CustomPacketPayload.codec(Gone::write, ::Gone)
    private val SPOTTED = CustomPacketPayload.Type<Spotted>(Substratum.id("smiler_spotted"))
    private val SPOTTED_CODEC: StreamCodec<FriendlyByteBuf, Spotted> = CustomPacketPayload.codec(Spotted::write, ::Spotted)
    private val STRUCK = CustomPacketPayload.Type<Struck>(Substratum.id("smiler_struck"))
    private val STRUCK_CODEC: StreamCodec<FriendlyByteBuf, Struck> = CustomPacketPayload.codec(Struck::write, ::Struck)

    fun register() {
        NetworkManager.registerReceiver(NetworkManager.Side.C2S, TYPE, CODEC) { gone, context ->
            val player = context.player as? ServerPlayer ?: return@registerReceiver
            context.queue { SmilerDirector.lost(player, gone.entity) }
        }
        NetworkManager.registerReceiver(NetworkManager.Side.C2S, SPOTTED, SPOTTED_CODEC) { spotted, context ->
            val player = context.player as? ServerPlayer ?: return@registerReceiver
            if (!spotted.valid()) return@registerReceiver
            context.queue { SmilerDirector.spotted(player, spotted.entity, spotted.eye) }
        }
        NetworkManager.registerReceiver(NetworkManager.Side.C2S, STRUCK, STRUCK_CODEC) { struck, context ->
            val player = context.player as? ServerPlayer ?: return@registerReceiver
            context.queue { SmilerDirector.struck(player, struck.entity) }
        }
    }

    fun gone(entity: Int) {
        NetworkManager.sendToServer(Gone(entity))
    }

    fun spotted(entity: Int, eye: Vec3) {
        NetworkManager.sendToServer(Spotted(entity, eye))
    }

    fun struck(entity: Int) {
        NetworkManager.sendToServer(Struck(entity))
    }
}
