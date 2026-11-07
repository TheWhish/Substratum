package xyz.thewhish.substratum.mixin;

import com.google.common.collect.ImmutableMap;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.model.geom.LayerDefinitions;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import xyz.thewhish.substratum.client.Suit;

@Mixin(LayerDefinitions.class)
abstract class SuitModelMixin {

    @ModifyReturnValue(method = "createRoots", at = @At("RETURN"))
    private static Map<ModelLayerLocation, LayerDefinition> substratum$suit(Map<ModelLayerLocation, LayerDefinition> roots) {
        Map<ModelLayerLocation, LayerDefinition> suited = new HashMap<>(roots);
        LayerDefinition suit = Suit.layer();
        suited.put(ModelLayers.PLAYER, suit);
        suited.put(ModelLayers.PLAYER_SLIM, suit);
        return ImmutableMap.copyOf(suited);
    }
}
