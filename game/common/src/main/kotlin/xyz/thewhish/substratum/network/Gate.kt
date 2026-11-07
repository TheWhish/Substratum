package xyz.thewhish.substratum.network

import com.mojang.authlib.GameProfile
import com.mojang.util.UndashedUuid
import dev.architectury.event.events.common.LifecycleEvent
import dev.architectury.event.events.common.PlayerEvent
import dev.architectury.event.events.common.TickEvent
import dev.architectury.networking.NetworkManager
import dev.architectury.platform.Platform
import dev.architectury.utils.Env
import net.minecraft.Util
import net.minecraft.client.Minecraft
import net.minecraft.core.UUIDUtil
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import xyz.thewhish.substratum.Substratum
import xyz.thewhish.substratum.client.Link
import xyz.thewhish.substratum.client.Lobby

object Gate {
    private const val PATIENCE = 60_000L

    private object Release : CustomPacketPayload {
        override fun type(): CustomPacketPayload.Type<Release> = TYPE
    }

    private val TYPE = CustomPacketPayload.Type<Release>(Substratum.id("release"))
    private val CODEC: StreamCodec<FriendlyByteBuf, Release> = StreamCodec.unit(Release)

    private var released = false
    private var openedAt = 0L

    fun register() {
        if (Platform.getEnvironment() == Env.SERVER) {
            NetworkManager.registerS2CPayloadType(TYPE, CODEC)
            return
        }
        NetworkManager.registerReceiver(NetworkManager.Side.S2C, TYPE, CODEC) { _, context -> context.queue { Lobby.release(Minecraft.getInstance()) } }
        LifecycleEvent.SERVER_STARTED.register {
            released = false
            openedAt = 0L
        }
        TickEvent.SERVER_POST.register(::tick)
        PlayerEvent.PLAYER_JOIN.register { player -> if (released) NetworkManager.sendToPlayer(player, Release) }
    }

    @JvmStatic
    fun profile(name: String): GameProfile {
        val member = Link.state?.team?.members?.find { it.nick == name }
        val id = member?.uuid?.let { runCatching { UndashedUuid.fromString(it) }.getOrNull() }
        return if (id != null && guarded()) GameProfile(id, name) else UUIDUtil.createOfflineProfile(name)
    }

    @JvmStatic
    fun admits(profile: GameProfile): Boolean {
        if (!guarded()) return true
        val participants = Link.hostedSession()?.participants ?: return true
        return UndashedUuid.toString(profile.id) in participants
    }

    private fun guarded(): Boolean = (Link.hostedSession()?.participants?.size ?: 0) > 1

    private fun tick(server: MinecraftServer) {
        if (released) return
        val session = Link.hostedSession()
        if (session == null || session.participants.size <= 1) return release(server)
        if (!session.open) return
        val now = Util.getMillis()
        if (openedAt == 0L) openedAt = now
        val online = server.playerList.players.map { UndashedUuid.toString(it.uuid) }
        if (online.containsAll(session.participants) || now - openedAt >= PATIENCE) release(server)
    }

    private fun release(server: MinecraftServer) {
        released = true
        server.playerList.players.forEach { player: ServerPlayer -> NetworkManager.sendToPlayer(player, Release) }
    }
}
