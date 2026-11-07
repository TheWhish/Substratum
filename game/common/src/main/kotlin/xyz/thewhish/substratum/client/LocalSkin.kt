package xyz.thewhish.substratum.client

import com.mojang.authlib.GameProfile
import net.minecraft.client.Minecraft
import net.minecraft.client.resources.PlayerSkin
import net.minecraft.resources.ResourceLocation
import xyz.thewhish.substratum.Substratum

object LocalSkin {
    private const val DEFAULT = "yellow"
    private val skins = HashMap<String, PlayerSkin?>()

    @JvmStatic
    fun of(profile: GameProfile): PlayerSkin? {
        val own = profile.id == Minecraft.getInstance().user.profileId
        val id = (if (own) Launcher.skin else Lobby.skinOf(profile.id)) ?: DEFAULT
        if (id !in skins) skins[id] = load(id)
        return skins[id]
    }

    private fun load(id: String): PlayerSkin? {
        val texture = ResourceLocation.tryBuild(Substratum.ID, "textures/skin/$id.png")
        if (texture == null || Minecraft.getInstance().resourceManager.getResource(texture).isEmpty) {
            Substratum.LOGGER.error("Unknown suit skin {}", id)
            return null
        }
        return PlayerSkin(texture, null, null, null, PlayerSkin.Model.WIDE, true)
    }
}
