package xyz.thewhish.substratum.level

import dev.architectury.event.events.common.LifecycleEvent
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.dimension.DimensionType
import net.minecraft.world.level.storage.LevelResource
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.util.Comparator
import kotlin.io.path.exists

object Epoch {
    private const val BENCH_EPOCH = 0x5EB57A7E5EB57A7EL
    private const val MARK = "epoch"
    private val random = SecureRandom()

    val frozen = System.getenv("SUBSTRATUM_BENCH") != null

    var id: Long = next()
        private set

    fun register() {
        LifecycleEvent.SERVER_BEFORE_START.register(::begin)
    }

    private fun begin(server: MinecraftServer) {
        id = next()
        val folder = storage(server)
        val mark = folder.resolve(MARK)
        if (frozen && mark.exists() && Files.readString(mark).trim() == id.toString()) return
        wipe(folder)
        if (frozen) {
            Files.createDirectories(folder)
            Files.writeString(mark, id.toString())
        }
    }

    private fun next(): Long = if (frozen) BENCH_EPOCH else random.nextLong()

    private fun storage(server: MinecraftServer): Path {
        val root = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize()
        val folder = DimensionType.getStorageFolder(SubstratumLevels.LEVEL_0, root).toAbsolutePath().normalize()
        require(folder.startsWith(root) && folder != root) { "Level 0 storage $folder escapes the world folder $root" }
        return folder
    }

    private fun wipe(folder: Path) {
        if (!folder.exists()) return
        try {
            Files.walk(folder).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        } catch (e: IOException) {
            throw IllegalStateException("could not reset Level 0 storage at $folder", e)
        }
    }

    fun salt(seed: Long): Long = seed xor id
}
