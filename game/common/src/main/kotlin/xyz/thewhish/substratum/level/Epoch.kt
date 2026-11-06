package xyz.thewhish.substratum.level

import dev.architectury.event.events.common.LifecycleEvent
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.dimension.DimensionType
import net.minecraft.world.level.storage.LevelResource
import java.io.IOException
import java.nio.file.Files
import java.security.SecureRandom
import java.util.Comparator
import kotlin.io.path.exists

object Epoch {
    private val random = SecureRandom()

    var id: Long = random.nextLong()
        private set

    fun register() {
        LifecycleEvent.SERVER_BEFORE_START.register(::begin)
    }

    private fun begin(server: MinecraftServer) {
        id = random.nextLong()
        wipe(server)
    }

    private fun wipe(server: MinecraftServer) {
        val root = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize()
        val folder = DimensionType.getStorageFolder(SubstratumLevels.LEVEL_0, root).toAbsolutePath().normalize()
        require(folder.startsWith(root) && folder != root) { "Level 0 storage $folder escapes the world folder $root" }
        if (!folder.exists()) return
        try {
            Files.walk(folder).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        } catch (e: IOException) {
            throw IllegalStateException("could not reset Level 0 storage at $folder", e)
        }
    }

    fun salt(seed: Long): Long = seed xor id
}
