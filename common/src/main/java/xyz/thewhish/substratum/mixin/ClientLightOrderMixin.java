package xyz.thewhish.substratum.mixin;

import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.util.Deque;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import xyz.thewhish.substratum.client.Atmosphere;

@Mixin(ClientLevel.class)
abstract class ClientLightOrderMixin {

    @Shadow @Final private Deque<Runnable> lightUpdateQueue;

    @Unique private final LongOpenHashSet substratum$unlit = new LongOpenHashSet();

    @Unique private boolean substratum$fresh = true;

    @Inject(method = "setServerVerifiedBlockState", at = @At("HEAD"))
    private void substratum$rememberUnlit(BlockPos pos, BlockState state, int flags, CallbackInfo ci) {
        if (!((ClientLevel) (Object) this).getLightEngine().lightOnInSection(SectionPos.of(pos))) this.substratum$unlit.add(pos.asLong());
    }

    @Inject(method = "pollLightUpdates", at = @At("HEAD"))
    private void substratum$drainBacklog(CallbackInfo ci) {
        if (!this.substratum$fresh) return;
        for (Runnable task = this.lightUpdateQueue.poll(); task != null; task = this.lightUpdateQueue.poll()) task.run();
    }

    @Inject(method = "pollLightUpdates", at = @At("RETURN"))
    private void substratum$recheckLit(CallbackInfo ci) {
        boolean fresh = this.substratum$fresh;
        this.substratum$fresh = false;
        if (this.substratum$unlit.isEmpty() && !fresh) return;
        LevelLightEngine engine = ((ClientLevel) (Object) this).getLightEngine();
        ObjectArrayList<BlockPos> lit = this.substratum$lit(engine);
        if (lit.isEmpty() && !fresh) return;
        engine.runLightUpdates();
        lit.forEach(engine::checkBlock);
        if (!fresh) return;
        engine.runLightUpdates();
        Atmosphere.settleLight(Minecraft.getInstance());
    }

    @Unique
    private ObjectArrayList<BlockPos> substratum$lit(LevelLightEngine engine) {
        ObjectArrayList<BlockPos> lit = new ObjectArrayList<>();
        ClientLevel level = (ClientLevel) (Object) this;
        LongIterator iterator = this.substratum$unlit.iterator();
        while (iterator.hasNext()) {
            BlockPos pos = BlockPos.of(iterator.nextLong());
            if (!level.hasChunk(SectionPos.blockToSectionCoord(pos.getX()), SectionPos.blockToSectionCoord(pos.getZ()))) {
                iterator.remove();
            } else if (engine.lightOnInSection(SectionPos.of(pos))) {
                lit.add(pos);
                iterator.remove();
            }
        }
        return lit;
    }
}
