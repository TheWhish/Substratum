package xyz.thewhish.substratum.client;

import net.minecraft.Util;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import xyz.thewhish.substratum.level.SubstratumLevels;
import xyz.thewhish.substratum.worldgen.MazeChunkGenerator;

public final class ShaftDarkness {

    private static final double SHAFT_TOP_Y = MazeChunkGenerator.FLOOR_Y + 1.0;

    private static final double FULL_DEPTH = 4.0;

    private static final double SKY_FADE_DISTANCE = 150.0;

    private static final double SKY_FADE_MAX_MILLIS = 4000.0;

    private static Entity skyViewer;

    private static double skyEntryY;

    private static long skyStartMillis;

    private ShaftDarkness() {
    }

    public static double darkAmount(ClientLevel level, Camera camera) {
        double shaft = shaftDepth(level, camera);
        return shaft > 0.0 ? shaft : skyFadeAmount(camera);
    }

    public static void beginSkyFade(Entity viewer) {
        skyViewer = viewer;
        skyEntryY = viewer.getEyeY();
        skyStartMillis = Util.getMillis();
    }

    public static void reset() {
        skyViewer = null;
    }

    private static double shaftDepth(ClientLevel level, Camera camera) {
        if (level == null || level.dimension() != SubstratumLevels.INSTANCE.getLEVEL_0()) {
            return 0.0;
        }
        if (camera.getEntity().level().dimension() != SubstratumLevels.INSTANCE.getLEVEL_0()) {
            return 0.0;
        }
        return Mth.clamp((SHAFT_TOP_Y - camera.getPosition().y) / FULL_DEPTH, 0.0, 1.0);
    }

    private static double skyFadeAmount(Camera camera) {
        if (skyViewer == null) {
            return 0.0;
        }
        if (skyViewer != camera.getEntity()) {
            skyViewer = null;
            return 0.0;
        }
        double byDepth = (camera.getPosition().y - (skyEntryY - SKY_FADE_DISTANCE)) / SKY_FADE_DISTANCE;
        double byTime = 1.0 - (Util.getMillis() - skyStartMillis) / SKY_FADE_MAX_MILLIS;
        double linear = Math.min(byDepth, byTime);
        if (linear <= 0.0) {
            skyViewer = null;
            return 0.0;
        }
        return Mth.smoothstep(Math.min(linear, 1.0));
    }
}
