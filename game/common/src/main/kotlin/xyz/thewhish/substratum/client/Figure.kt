package xyz.thewhish.substratum.client

import com.mojang.authlib.GameProfile
import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.model.PlayerModel
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.player.RemotePlayer
import net.minecraft.client.resources.PlayerSkin

abstract class Figure(level: ClientLevel, profile: GameProfile) : RemotePlayer(level, profile) {

    override fun getSkin(): PlayerSkin = LocalSkin.of(gameProfile) ?: super.getSkin()

    abstract fun slump(poseStack: PoseStack)

    abstract fun pose(model: PlayerModel<*>)
}
