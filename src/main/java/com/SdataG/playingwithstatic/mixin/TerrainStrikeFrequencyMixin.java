package com.SdataG.playingwithstatic.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Terrain-aware strike frequency (locked decision 2 / 9). Vanilla's own per-chunk-per-tick roll during
 * a storm is {@code this.random.nextInt(100000) == 0} in {@link ServerLevel#tickChunk}, checked here by
 * source inspection of the actual 1.21.1 NeoForge sources jar rather than assumed. We don't touch WHERE
 * within a chunk a strike lands (vanilla's own heightmap + rod-redirect logic runs entirely
 * afterward, untouched) -- only how likely a given chunk's roll is to succeed at all, scaled by real
 * atmospheric-physics stand-ins available from vanilla data: elevation and biome climate.
 *
 * <p>P = B · M_height · M_climate, where B = 1/100000 is vanilla's own base chance. Implemented by
 * redirecting the {@code nextInt} bound: since P = 1/bound, bound = 100000 / (M_height · M_climate).</p>
 *
 * <ul>
 * <li>M_height = 1 + α·(max(0, (Y-64)/(320-64)))², α = 10. Quadratic and floored at Y=64 (sea level) so
 * it's gentle at ordinary mountain heights and only sharply punishing near the actual build limit — a
 * Y128 base sees ~1.6x, Y192 ~3.5x, Y320 the full 11x.</li>
 * <li>M_climate = 1 + β·(T·H), β = 2.5, using the sampled biome's temperature and downfall. Jungles
 * (~3.1x) and badlands see more strikes; snowy/dry biomes (as low as ~0.5x) see fewer. Floored well
 * above zero so a very cold+humid biome can't produce a negative or zero bound.</li>
 * </ul>
 *
 * <p><b>Combined multiplier is capped</b> at {@code COMBINED_MULT_CAP}, separately from either factor's
 * own individual range. Worst case, the two multiply: a Y320 jungle mountaintop would be
 * {@code 11 * ~3.1 ≈ 34x} vanilla's base rate uncapped — a bolt every few seconds instead of an
 * occasional dramatic event, which stops reading as "dangerous" and starts reading as "broken." The cap
 * only bites when both factors are pushed toward their own extremes at once; either alone can still
 * reach close to its individual max.</p>
 *
 * <p>Sampled once per chunk-tick at that chunk's center, not per-block -- matches "how often a chunk
 * gets picked", not "where in it", and keeps the cost to one heightmap query and one biome lookup per
 * already-throttled random chunk tick.</p>
 */
@Mixin(ServerLevel.class)
public abstract class TerrainStrikeFrequencyMixin {

    private static final int BASE_BOUND = 100_000;

    private static final float HEIGHT_ALPHA = 10.0F;
    private static final float SEA_LEVEL = 64.0F;
    private static final float MAX_BUILD_HEIGHT = 320.0F;

    private static final float CLIMATE_BETA = 2.5F;
    private static final float CLIMATE_MULT_FLOOR = 0.05F;

    /** Ceiling on the COMBINED height*climate multiplier -- roughly half the theoretical uncapped max
     *  (~34x, a Y320 jungle mountaintop), so the worst-case stack still reads as "dangerous" rather than
     *  "a bolt every few seconds." Each factor can still reach close to its own individual max alone;
     *  this only bites when both are pushed toward their extremes at the same time. */
    private static final float COMBINED_MULT_CAP = 15.0F;

    @Redirect(
            method = "tickChunk",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/util/RandomSource;nextInt(I)I"),
            require = 0)
    private int playingwithstatic$terrainScaledStrikeRoll(RandomSource random, int bound, LevelChunk chunk) {
        if (bound != BASE_BOUND) {
            return random.nextInt(bound); // tickChunk's OTHER nextInt call (ice/snow, bound 48) -- untouched
        }

        ServerLevel level = (ServerLevel) (Object) this;
        ChunkPos chunkPos = chunk.getPos();
        int sampleX = chunkPos.getMinBlockX() + 8;
        int sampleZ = chunkPos.getMinBlockZ() + 8;
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, sampleX, sampleZ);

        Holder<Biome> biome = level.getBiome(new BlockPos(sampleX, y, sampleZ));
        Biome.ClimateSettings climate = biome.value().getModifiedClimateSettings();

        float heightFrac = Math.max(0.0F, (y - SEA_LEVEL) / (MAX_BUILD_HEIGHT - SEA_LEVEL));
        float heightMult = 1.0F + HEIGHT_ALPHA * heightFrac * heightFrac;
        float climateMult = Math.max(CLIMATE_MULT_FLOOR,
                1.0F + CLIMATE_BETA * (climate.temperature() * climate.downfall()));
        float combinedMult = Math.min(COMBINED_MULT_CAP, heightMult * climateMult);

        int scaledBound = Math.max(1, Math.round(bound / combinedMult));
        return random.nextInt(scaledBound);
    }
}
