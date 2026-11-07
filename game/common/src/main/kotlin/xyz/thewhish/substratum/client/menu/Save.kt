package xyz.thewhish.substratum.client.menu

import dev.architectury.platform.Platform
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen
import net.minecraft.world.Difficulty
import net.minecraft.world.level.GameRules
import net.minecraft.world.level.GameType
import net.minecraft.world.level.LevelSettings
import net.minecraft.world.level.WorldDataConfiguration
import net.minecraft.world.level.levelgen.WorldOptions
import net.minecraft.world.level.levelgen.presets.WorldPresets
import xyz.thewhish.substratum.Substratum
import java.io.IOException
import java.nio.file.Files
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object Save {
    private const val FOLDER = "substratum"
    private val STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")

    fun exists(): Boolean = Minecraft.getInstance().levelSource.levelExists(FOLDER)

    fun resume(menu: Screen) {
        val minecraft = Minecraft.getInstance()
        minecraft.createWorldOpenFlows().openWorld(FOLDER) { minecraft.setScreen(menu) }
    }

    fun archive(): Boolean {
        val minecraft = Minecraft.getInstance()
        val world = minecraft.levelSource.baseDir.resolve(FOLDER)
        if (Files.notExists(world)) return true
        val target = minecraft.gameDirectory.toPath()
            .resolve("archive")
            .resolve("$FOLDER-${LocalDateTime.now().format(STAMP)}")
        return try {
            Files.createDirectories(target.parent)
            Files.move(world, target)
            true
        } catch (error: IOException) {
            Substratum.LOGGER.error("Failed to archive the game {} to {}", world, target, error)
            false
        }
    }

    fun create(menu: Screen) {
        val settings = LevelSettings(
            FOLDER,
            GameType.SURVIVAL,
            false,
            Difficulty.NORMAL,
            Platform.isDevelopmentEnvironment(),
            GameRules(),
            WorldDataConfiguration.DEFAULT,
        )
        val options = WorldOptions(WorldOptions.randomSeed(), true, false)
        Minecraft.getInstance().createWorldOpenFlows()
            .createFreshLevel(FOLDER, settings, options, WorldPresets::createNormalWorldDimensions, menu)
    }
}
