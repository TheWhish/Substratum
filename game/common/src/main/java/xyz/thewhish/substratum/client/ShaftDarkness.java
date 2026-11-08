package xyz.thewhish.substratum.client;

import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;
import xyz.thewhish.substratum.level.SubstratumLevels;
import xyz.thewhish.substratum.worldgen.MazeChunkGenerator;

public final class ShaftDarkness {

    private static final double SHAFT_TOP_Y = MazeChunkGenerator.FLOOR_Y + 1.0;

    private static final double FULL_DEPTH = 4.0;

    private ShaftDarkness() {
    }

    public static double darkAmount(ClientLevel level, Camera camera) {
        if (level == null || level.dimension() != SubstratumLevels.INSTANCE.getLEVEL_0()) {
            return 0.0;
        }
        if (camera.getEntity().level().dimension() != SubstratumLevels.INSTANCE.getLEVEL_0()) {
            return 0.0;
        }
        return Mth.clamp((SHAFT_TOP_Y - camera.getPosition().y) / FULL_DEPTH, 0.0, 1.0);
    }
}
