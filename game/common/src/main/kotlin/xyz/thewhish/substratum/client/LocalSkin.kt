package xyz.thewhish.substratum.client

import com.mojang.authlib.GameProfile
import com.mojang.blaze3d.platform.NativeImage
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.client.resources.PlayerSkin
import xyz.thewhish.substratum.Substratum
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

object LocalSkin {
    private const val SIDE = 64
    private val texture = Substratum.id("local_skin")
    private val skin: PlayerSkin? by lazy(::load)

    @JvmStatic
    fun of(profile: GameProfile): PlayerSkin? =
        if (profile.id == Minecraft.getInstance().user.profileId) skin else null

    private fun load(): PlayerSkin? {
        val file = System.getProperty("substratum.skin") ?: return null
        val image = try {
            Files.newInputStream(Path.of(file)).use { NativeImage.read(it) }
        } catch (error: IOException) {
            Substratum.LOGGER.error("Failed to read the launcher skin {}", file, error)
            return null
        }
        if (image.width != SIDE || image.height != SIDE) {
            Substratum.LOGGER.error("The launcher skin {} is {}x{}, expected {}x{}", file, image.width, image.height, SIDE, SIDE)
            image.close()
            return null
        }
        Minecraft.getInstance().textureManager.register(texture, DynamicTexture(image))
        return PlayerSkin(texture, null, null, null, PlayerSkin.Model.WIDE, true)
    }
}
