package xyz.thewhish.substratum.neoforge;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import xyz.thewhish.substratum.Substratum;
import xyz.thewhish.substratum.registry.ModBlockEntities;

@Mod(Substratum.ID)
public final class SubstratumNeoForge {

    public SubstratumNeoForge(IEventBus modBus) {
        ModBlockEntities.INSTANCE.setFactory(RiftCutNeoForgeBlockEntity::new);
        Substratum.INSTANCE.init();
        if (FMLEnvironment.dist.isClient()) {
            SubstratumNeoForgeClient.INSTANCE.init(modBus);
        }
    }
}
