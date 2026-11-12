package xyz.thewhish.substratum.level

import dev.architectury.event.events.common.LifecycleEvent
import dev.architectury.event.events.common.PlayerEvent
import dev.architectury.event.events.common.TickEvent
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.NbtAccounter
import net.minecraft.nbt.NbtIo
import net.minecraft.nbt.NbtUtils
import net.minecraft.nbt.Tag
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.util.Mth
import net.minecraft.world.level.GameType
import net.minecraft.world.phys.Vec3
import net.minecraft.world.level.storage.LevelResource
import xyz.thewhish.substratum.Substratum
import xyz.thewhish.substratum.client.Collapse
import xyz.thewhish.substratum.network.FeedSync
import xyz.thewhish.substratum.network.Gate
import xyz.thewhish.substratum.sanity.SanityTracker
import xyz.thewhish.substratum.worldgen.MazeLayout
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID

object Run {
    private const val FILE = "substratum-run.dat"
    private const val SHIFT = 4096
    private const val SILENCE = 60
    private const val BYTE_TURN = 256f

    private class Entry(val items: ListTag, val sanity: Int)

    private val state = RunState<Entry>()
    private val landings = HashMap<UUID, Footprint.Landing>()
    private var silenceAt = -1
    private var born = 0L

    val level: Int get() = state.level

    fun register() {
        LifecycleEvent.SERVER_STARTED.register(::load)
        PlayerEvent.PLAYER_JOIN.register(::joined)
        PlayerEvent.PLAYER_QUIT.register(::left)
        TickEvent.SERVER_POST.register(::tick)
    }

    private fun channels(players: List<ServerPlayer>): List<ServerPlayer> = players.filter { state.watchable(it.uuid) }.sortedBy { it.uuid }

    fun watch(player: ServerPlayer, target: UUID) {
        if (!state.isLost(player.uuid) || state.isDying(player.uuid)) return
        channels(player.server.playerList.players).firstOrNull { it.uuid == target }?.let(player::setCamera)
    }

    fun begin(server: MinecraftServer) {
        val players = server.playerList.players
        if (players.isEmpty() || Epoch.frozen || !state.begin()) return
        born = server.overworld().gameTime
        players.forEach { apply(it, state.entry(it.uuid)) }
        start(server, players)
    }

    fun falling(player: ServerPlayer): Boolean = state.isDying(player.uuid)

    fun lose(player: ServerPlayer) {
        val server = player.server
        if (!state.lose(player.uuid, until = server.tickCount + Collapse.TICKS)) return
        val found = Remains.landing(player)
        val landing = found.copy(yaw = Mth.floor(found.yaw * BYTE_TURN / 360f).toByte() * 360f / BYTE_TURN)
        landings[player.uuid] = landing
        FeedSync.fall(server, player.uuid, landing)
    }

    private fun die(player: ServerPlayer) {
        Remains.leave(player, landings.remove(player.uuid) ?: Footprint.Landing(player.yRot, Vec3.ZERO))
        SanityTracker.set(player, 0)
        spectate(player)
    }

    fun advance(server: MinecraftServer) {
        if (!state.begun) return
        val players = server.playerList.players
        state.advance(players.associate { it.uuid to snapshot(it) })
        write(server)
        start(server, players)
    }

    private fun load(server: MinecraftServer) {
        silenceAt = -1
        landings.clear()
        val file = file(server)
        if (!Files.exists(file)) return state.load(0, emptyMap())
        val root = try {
            NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap())
        } catch (e: IOException) {
            throw IllegalStateException("could not read the run at $file", e)
        }
        val entries = HashMap<UUID, Entry>()
        for (tag in root.getList("players", Tag.TAG_COMPOUND.toInt())) {
            val player = tag as CompoundTag
            val id = player.get("uuid") ?: continue
            entries[NbtUtils.loadUUID(id)] = Entry(player.getList("items", Tag.TAG_COMPOUND.toInt()), player.getInt("sanity"))
        }
        state.load(root.getInt("level"), entries)
    }

    private fun write(server: MinecraftServer) {
        val players = ListTag()
        for ((uuid, entry) in state.save) {
            players.add(CompoundTag().apply {
                put("uuid", NbtUtils.createUUID(uuid))
                put("items", entry.items)
                putInt("sanity", entry.sanity)
            })
        }
        val root = CompoundTag().apply {
            putInt("level", state.level)
            put("players", players)
        }
        val file = file(server)
        val temporary = file.resolveSibling("$FILE.tmp")
        NbtIo.writeCompressed(root, temporary)
        Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        Substratum.LOGGER.info("Run saved at level {} for {} players", state.level, state.save.size)
    }

    private fun joined(player: ServerPlayer) {
        when {
            state.begun -> {
                apply(player, state.join(player.uuid))
                spectate(player)
                if (silenceAt >= 0) FeedSync.signal(player, live = false)
                FeedSync.tape(player, born, state.level)
            }
            Gate.released -> begin(player.server)
            player.level().dimension() != SubstratumLevels.LEVEL_0 -> Arrival.send(player)
        }
    }

    private fun left(player: ServerPlayer) {
        if (landings.remove(player.uuid) != null) FeedSync.rise(player.server, player.uuid)
        state.leave(player.uuid, snapshot(player))
    }

    private fun tick(server: MinecraftServer) {
        val players = server.playerList.players
        if (!state.begun || players.isEmpty()) return
        val fallen = state.expire(server.tickCount)
        players.filter { it.uuid in fallen }.forEach(::die)
        if (state.fallen(players.map { it.uuid })) return fade(server, players)
        silenceAt = -1
        val channels = channels(players)
        for (player in players) {
            if (!state.isLost(player.uuid) || state.isDying(player.uuid)) continue
            if (!player.isSpectator) player.setGameMode(GameType.SPECTATOR)
            val camera = player.camera
            if (channels.none { it === camera }) player.setCamera(channels.first())
        }
    }

    private fun fade(server: MinecraftServer, players: List<ServerPlayer>) {
        val now = server.tickCount
        if (silenceAt < 0) {
            silenceAt = now
            players.forEach { FeedSync.signal(it, live = false) }
        }
        if (now - silenceAt < SILENCE) return
        silenceAt = -1
        state.wipe()
        born = server.overworld().gameTime
        players.forEach { apply(it, null) }
        start(server, players)
        players.forEach { FeedSync.signal(it, live = true) }
    }

    private fun start(server: MinecraftServer, players: List<ServerPlayer>) {
        landings.keys.forEach { FeedSync.rise(server, it) }
        landings.clear()
        for (player in players) {
            player.setCamera(player)
            player.setGameMode(server.defaultGameType)
            FeedSync.tape(player, born, state.level)
        }
        Arrival.spread(server, players, MazeLayout.CENTRE + state.area * SHIFT, MazeLayout.CENTRE)
    }

    private fun spectate(player: ServerPlayer) {
        player.setGameMode(GameType.SPECTATOR)
        channels(player.server.playerList.players).firstOrNull { it !== player }?.let(player::setCamera)
    }

    private fun apply(player: ServerPlayer, entry: Entry?) {
        player.inventory.clearContent()
        if (entry != null) player.inventory.load(entry.items)
        SanityTracker.set(player, entry?.sanity ?: 0)
    }

    private fun snapshot(player: ServerPlayer): Entry = Entry(player.inventory.save(ListTag()), SanityTracker.ticks(player))

    private fun file(server: MinecraftServer): Path = server.getWorldPath(LevelResource.ROOT).resolve(FILE)
}
