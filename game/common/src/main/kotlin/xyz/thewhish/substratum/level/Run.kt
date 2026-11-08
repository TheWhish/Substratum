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
import net.minecraft.world.level.GameType
import net.minecraft.world.level.storage.LevelResource
import xyz.thewhish.substratum.Substratum
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

    private class Entry(val items: ListTag, val sanity: Int)

    private var number = 0
    private val saved = HashMap<UUID, Entry>()
    private val departed = HashMap<UUID, Entry>()
    private val lost = HashSet<UUID>()
    private var begun = false
    private var silenceAt = -1
    private var area = 0

    val level: Int get() = number

    fun register() {
        LifecycleEvent.SERVER_STARTED.register(::load)
        PlayerEvent.PLAYER_JOIN.register(::joined)
        PlayerEvent.PLAYER_QUIT.register(::left)
        TickEvent.SERVER_POST.register(::tick)
    }

    fun alive(players: List<ServerPlayer>): List<ServerPlayer> = players.filter { it.uuid !in lost }.sortedBy { it.uuid }

    fun turn(player: ServerPlayer, forward: Boolean) {
        if (player.uuid !in lost) return
        val alive = alive(player.server.playerList.players)
        if (alive.isEmpty()) return
        val current = alive.indexOfFirst { it === player.camera }
        val next = if (current < 0) 0 else Math.floorMod(current + if (forward) 1 else -1, alive.size)
        player.setCamera(alive[next])
    }

    fun begin(server: MinecraftServer) {
        val players = server.playerList.players
        if (begun || players.isEmpty() || Epoch.frozen) return
        begun = true
        players.forEach { apply(it, saved[it.uuid]) }
        start(server, players)
    }

    fun lose(player: ServerPlayer) {
        if (!begun || !lost.add(player.uuid)) return
        Remains.leave(player)
        SanityTracker.set(player, 0)
        spectate(player)
    }

    fun advance(server: MinecraftServer) {
        if (!begun) return
        number++
        val players = server.playerList.players
        saved.putAll(departed)
        departed.clear()
        players.forEach { saved[it.uuid] = snapshot(it) }
        write(server)
        move()
        start(server, players)
    }

    private fun load(server: MinecraftServer) {
        begun = false
        number = 0
        saved.clear()
        departed.clear()
        lost.clear()
        area = 0
        val file = file(server)
        if (!Files.exists(file)) return
        val root = try {
            NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap())
        } catch (e: IOException) {
            throw IllegalStateException("could not read the run at $file", e)
        }
        number = root.getInt("level")
        for (tag in root.getList("players", Tag.TAG_COMPOUND.toInt())) {
            val player = tag as CompoundTag
            val id = player.get("uuid") ?: continue
            saved[NbtUtils.loadUUID(id)] = Entry(player.getList("items", Tag.TAG_COMPOUND.toInt()), player.getInt("sanity"))
        }
    }

    private fun write(server: MinecraftServer) {
        val players = ListTag()
        for ((uuid, entry) in saved) {
            players.add(CompoundTag().apply {
                put("uuid", NbtUtils.createUUID(uuid))
                put("items", entry.items)
                putInt("sanity", entry.sanity)
            })
        }
        val root = CompoundTag().apply {
            putInt("level", number)
            put("players", players)
        }
        val file = file(server)
        val temporary = file.resolveSibling("$FILE.tmp")
        NbtIo.writeCompressed(root, temporary)
        Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        Substratum.LOGGER.info("Run saved at level {} for {} players", number, saved.size)
    }

    private fun joined(player: ServerPlayer) {
        when {
            begun -> {
                departed.remove(player.uuid)
                lost.add(player.uuid)
                spectate(player)
                if (silenceAt >= 0) FeedSync.signal(player, live = false)
            }
            Gate.released -> begin(player.server)
            player.level().dimension() != SubstratumLevels.LEVEL_0 -> Arrival.send(player)
        }
    }

    private fun left(player: ServerPlayer) {
        if (!begun) return
        departed[player.uuid] = snapshot(player)
        lost.remove(player.uuid)
    }

    private fun tick(server: MinecraftServer) {
        if (!begun) return
        val players = server.playerList.players
        if (players.isEmpty()) return
        val alive = alive(players)
        if (alive.isEmpty()) return fade(server, players)
        silenceAt = -1
        for (player in players) {
            if (player.uuid !in lost) continue
            if (!player.isSpectator) player.setGameMode(GameType.SPECTATOR)
            val camera = player.camera
            if (alive.none { it === camera }) player.setCamera(alive.first())
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
        wipe(server)
        players.forEach { FeedSync.signal(it, live = true) }
    }

    private fun wipe(server: MinecraftServer) {
        val players = server.playerList.players
        players.forEach { apply(it, null) }
        move()
        start(server, players)
    }

    private fun start(server: MinecraftServer, players: List<ServerPlayer>) {
        lost.clear()
        for (player in players) {
            player.setCamera(player)
            player.setGameMode(server.defaultGameType)
        }
        Arrival.spread(server, players, MazeLayout.CENTRE + area * SHIFT, MazeLayout.CENTRE)
    }

    private fun move() {
        area++
    }

    private fun spectate(player: ServerPlayer) {
        player.setGameMode(GameType.SPECTATOR)
        alive(player.server.playerList.players).firstOrNull()?.let(player::setCamera)
    }

    private fun apply(player: ServerPlayer, entry: Entry?) {
        player.inventory.clearContent()
        if (entry != null) player.inventory.load(entry.items)
        SanityTracker.set(player, entry?.sanity ?: 0)
    }

    private fun snapshot(player: ServerPlayer): Entry = Entry(player.inventory.save(ListTag()), SanityTracker.ticks(player))

    private fun file(server: MinecraftServer): Path = server.getWorldPath(LevelResource.ROOT).resolve(FILE)
}
