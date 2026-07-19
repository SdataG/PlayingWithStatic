package com.SdataG.playingwithstatic.client.render;

import net.minecraft.core.BlockPos;

import java.util.concurrent.ConcurrentHashMap;

/**
 * The in-world lighting impact of an active bolt: nearby BLOCK light flares at the strike point during
 * the flash and fades with the bolt's afterglow, instead of the strike being purely additive geometry
 * with no actual light of its own.
 *
 * <p>Single writer (the render thread, once per bolt per frame via {@link BoltRenderer}); read from any
 * thread (chunk mesh workers) via {@code LightningFlashLightMixin}, so a plain {@link
 * ConcurrentHashMap} is enough. Entries self-expire (a bolt that vanishes mid-render without a final
 * update call would otherwise leave a permanent stuck light).</p>
 */
public final class BoltFlashLight {

    private static final long EXPIRY_NANOS = 500_000_000L; // 0.5s -- generous past the bolt's ~0.5s life

    private record Flash(int x, int y, int z, float intensity, float radius, long expiresAtNanos) {
    }

    private static final ConcurrentHashMap<Integer, Flash> ACTIVE = new ConcurrentHashMap<>();

    private BoltFlashLight() {
    }

    /** Called once per rendered frame for each live bolt, keyed by its entity id. */
    public static void update(int boltId, BlockPos strike, float intensity, float radius) {
        if (intensity <= 0.01F) {
            ACTIVE.remove(boltId);
            return;
        }
        ACTIVE.put(boltId, new Flash(strike.getX(), strike.getY(), strike.getZ(), intensity, radius,
                System.nanoTime() + EXPIRY_NANOS));
    }

    public static void clear(int boltId) {
        ACTIVE.remove(boltId);
    }

    /** Extra block light this position should read from every active flash nearby (0 if none). */
    public static int extraLightAt(int x, int y, int z) {
        if (ACTIVE.isEmpty()) {
            return 0;
        }
        long now = System.nanoTime();
        int best = 0;
        for (Flash f : ACTIVE.values()) {
            if (now > f.expiresAtNanos()) {
                continue; // stale; self-heals without needing an explicit sweep
            }
            float radius = f.radius();
            int dx = x - f.x();
            int dy = y - f.y();
            int dz = z - f.z();
            int distSq = dx * dx + dy * dy + dz * dz;
            if (distSq > radius * radius) {
                continue;
            }
            float dist = (float) Math.sqrt(distSq);
            float falloff = 1.0F - dist / radius;
            int level = Math.round(15.0F * f.intensity() * falloff);
            if (level > best) {
                best = level;
            }
        }
        return best;
    }
}
