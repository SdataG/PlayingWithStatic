package com.SdataG.playingwithstatic.integration;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import java.lang.reflect.Method;

/**
 * Optional, soft integration with Sunwell -- pure reflection, no compile-time dependency, never
 * required. Lets this mod's bolt renderer and sound mixin tell a Sunwell-owned lamp strike apart from a
 * genuine outdoor sky strike.
 *
 * <p>Both mods reuse vanilla's same {@code LightningBolt} entity rather than spawning their own (see
 * ROADMAP.md locked decision 2) -- Sunwell re-skins bolts that land in a lit lamp region, this mod
 * re-skins every natural strike. Without this check the two collide: this mod's full sky-to-ground bolt
 * and retimed thunder would render/play for Sunwell's lamp strikes too, visually stomping Sunwell's own
 * short lamp-to-ceiling bolt and doubling its thunder. With it, the two stay properly independent --
 * Sunwell's lamp lightning for Sunwell's strikes, this mod's sky lightning for everything else.</p>
 *
 * <p>Fail-open: if Sunwell isn't installed, or its API doesn't link the way this expects (a future
 * Sunwell update changed it), every check here returns false -- treat it as an ordinary sky strike --
 * rather than throwing. Reflection instead of a compileOnly dependency because the surface needed is
 * tiny (three methods) and this way the mod builds and runs identically whether or not Sunwell's jar
 * happens to be sitting in the test instance at build time.</p>
 */
public final class SunwellCompat {

    private static final Method GET;
    private static final Method NEAREST_SOURCE_ABOVE;
    private static final Method BASE_SKY_AT;

    static {
        Method get = null;
        Method nearestSourceAbove = null;
        Method baseSkyAt = null;
        try {
            Class<?> managerClass = Class.forName("com.SdataG.sunwell.SunwellManager");
            get = managerClass.getMethod("get", Level.class);
            nearestSourceAbove = managerClass.getMethod("nearestSourceAbove", BlockPos.class);
            baseSkyAt = managerClass.getMethod("baseSkyAt", BlockPos.class);
        } catch (ReflectiveOperationException | LinkageError ignored) {
            // Sunwell isn't installed, or its API changed -- fail open, isSunwellBolt always returns
            // false below.
        }
        GET = get;
        NEAREST_SOURCE_ABOVE = nearestSourceAbove;
        BASE_SKY_AT = baseSkyAt;
    }

    private SunwellCompat() {
    }

    /**
     * True if {@code strike} belongs to a Sunwell lamp rather than a genuine outdoor sky strike -- this
     * mod should render and retime nothing for it, leaving it entirely to Sunwell's own mixins. Mirrors
     * the exact ownership check Sunwell's own {@code SunwellBoltRenderer.tryRender} uses on itself.
     */
    public static boolean isSunwellBolt(Level level, BlockPos strike) {
        if (GET == null) {
            return false;
        }
        try {
            Object manager = GET.invoke(null, level);
            if (manager == null) {
                return false;
            }
            BlockPos source = (BlockPos) NEAREST_SOURCE_ABOVE.invoke(manager, strike);
            if (source != null) {
                return true;
            }
            int sky = (Integer) BASE_SKY_AT.invoke(manager, strike);
            return sky > 0;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException error) {
            return false;
        }
    }
}
