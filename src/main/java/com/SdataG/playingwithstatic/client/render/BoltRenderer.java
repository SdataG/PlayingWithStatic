package com.SdataG.playingwithstatic.client.render;

import com.SdataG.playingwithstatic.integration.SunwellCompat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The mod's own lightning: a jagged branching bolt that leaps down from directly above the strike
 * (locked decision 3 — same X/Z as vanilla's strike position, projected up to a fixed visual sky
 * height), forks and wisps on its way down, and sheds fading ghost layers as it dies.
 *
 * <p>Replaces the look, not the entity (locked decision 2). The bolt is still vanilla's
 * {@link LightningBolt} — the strike keeps every real behaviour (fire, damage, charged creepers,
 * copper oxidising, and crucially vanilla's own rod-redirect and per-chunk frequency roll). Only the
 * render is swapped, by {@code LightningBoltRendererMixin}. Every natural strike gets this treatment;
 * there is no ownership check the way Sunwell's version has one for a specific light source.</p>
 *
 * <p>Adapted from Sunwell's {@code SunwellBoltRenderer} (read, not copied wholesale — see the
 * project's ROADMAP.md "Prior art" section). The core difference: Sunwell's bolt could originate from
 * a lamp horizontally offset from the strike, so it bowed an arc across. Ours always starts directly
 * above the strike (same column), so it's a straight vertical channel with the same jag/branch/bow
 * wander for texture, not to bridge a horizontal gap.</p>
 */
public final class BoltRenderer {

    /** Roughly one jag point per this many blocks of length. Small = finer, twitchier lightning. */
    private static final float SEGMENT_LENGTH = 0.5F;

    /** How far mid-path jag points wander off the channel. 2x Sunwell's original 0.20. */
    private static final float JAG = 0.40F;

    /** Arc bow sideways, as a fraction of the channel's total (vertical) length -- top and bottom
     *  share the same X/Z, so this is what actually gives the channel its arch instead of a razor-
     *  straight drop; the branches fork off this same bowed path. Floored and capped so a very short
     *  or very tall drop still reads as a proper arc rather than none at all or an absurd sideways lean.
     *  A previous "2x" pass on this pushed the arc too far for a long diagonal trunk (up to ~25 blocks
     *  of sideways bow on an ~85-block channel) -- pulled back to a less extreme lean. */
    private static final float BOW_FRACTION = 0.14F;
    private static final float BOW_MIN = 0.5F;
    private static final float BOW_MAX = 15.0F;

    /** Thin bright core, soft glow, and a wide luminous bloom (the photo look). Whole effect is 2x
     *  Sunwell's original SunwellBoltRenderer sizing (0.035/0.018/0.13/0.42) -- a sky-to-ground bolt
     *  reads much smaller relative to open-air view distance than Sunwell's short lamp-to-ceiling one
     *  did, so it needs to be visually much thicker to read at all from a normal viewing distance. */
    private static final float CORE_WIDTH = 0.07F;
    /** Inner hyper-bright filament, thinner than the core -- fakes a bloom right at the white-hot centre. */
    private static final float CORE_HOT_WIDTH = 0.036F;
    private static final float HALO_WIDTH = 0.26F;
    private static final float BLOOM_WIDTH = 0.84F;

    /** Independent chance, rolled once per slot, of a major fork -- a channel nearly as bright/thick as
     *  the trunk, not the usual thin offshoots (see drawBranches). MAJOR_SPLIT_SLOTS slots at this
     *  chance each means a bolt can have more than one (expected ~1.6 major forks per bolt at these
     *  values, real lightning photos routinely show more than one prominent branch, not just the one
     *  dominant trunk). */
    private static final float MAJOR_SPLIT_CHANCE = 0.55F;
    private static final int MAJOR_SPLIT_SLOTS = 3;

    /** Vanilla's own fixed overworld cloud render height (confirmed in
     *  {@code DimensionSpecialEffects.OverworldEffects}, {@code CLOUD_LEVEL = 192}). The sky origin
     *  targets this height directly (see {@link #tryRender}) so the leader visually originates at/from
     *  the real cloud layer instead of an arbitrary offset that too often sat well below where clouds
     *  actually render. */
    private static final float CLOUD_LEVEL = 192.0F;

    /** Minimum height above the strike the sky origin sits at, even when the strike itself is already
     *  above {@link #CLOUD_LEVEL} (a tall player build) -- the leader always visibly falls at least this
     *  far. Also used as the fallback cap headroom below the level's real build height. */
    private static final float MIN_HEIGHT_ABOVE_STRIKE = 40.0F;

    /** How far horizontally the sky origin can land from the strike's own X/Z (0 = directly overhead).
     *  Random per bolt within this radius -- a genuine long reach across the sky instead of a straight
     *  top-to-bottom drop every time. */
    private static final float SKY_RADIUS = 30.0F;

    /** How far out (blocks) to search for an actual cloud puff to align the sky origin with (see
     *  {@link #cloudOffsetFor}), a bit past SKY_RADIUS to improve the odds of finding one. Falls back to
     *  the plain random offset within SKY_RADIUS if nothing qualifies within this radius. */
    private static final float CLOUD_SEARCH_RADIUS = 45.0F;
    private static final float CLOUD_SEARCH_STEP = 6.0F;

    /** Whole VFX length in ticks. Was a flat 10 (locked decision 4, matching Sunwell's timing). A
     *  previous pass bumped this to 14 with the leader stage taking 65%, which put the return stroke at
     *  tick ~9 -- too close to (and evidently sometimes past) vanilla's own {@code LightningBolt}
     *  entity's real worst-case lifespan (its {@code life}/{@code flashes} counters can discard it as
     *  early as ~8-9 ticks on an unlucky roll), causing bolts to sometimes get discarded before ever
     *  visibly reaching the ground. Pulled back to 11 ticks / tick ~6 for the return stroke, a safer
     *  margin under that floor while still slightly longer than the original 10/5. */
    private static final float LIFE_TICKS = 11.0F;

    /** Beat boundaries over the life: spread (leader), strike (return), fade after. Leader eases in --
     *  see {@link #LEADER_EASE_POWER} -- slow near the sky origin, accelerating as it nears the ground,
     *  instead of a constant linear crawl. */
    private static final float LEADER_END = 0.55F;
    private static final float RETURN_END = 0.69F;

    /** Power curve for the leader's growth fraction within its own phase: {@code reach = u^power}, where
     *  u is 0..1 progress through the leader phase. 1.0 = linear; higher = slower start (near the sky
     *  origin), faster finish (near the ground). A previous pass used 2.2, which -- combined with the
     *  longer leader phase above -- kept `reach` too low for too much of the entity's real (short)
     *  lifespan for most branches (spread across originFrac 0.12-0.9) to ever get a chance to grow.
     *  1.5 keeps a real ease-in feel without starving branches of the reach they need. */
    private static final float LEADER_EASE_POWER = 1.5F;

    /** The tick the return stroke lands on — when the thunder/impact sound should play. */
    public static final int STRIKE_TICK = Math.round(LEADER_END * LIFE_TICKS);

    /** How far out (blocks) to look for a copper lightning rod for branches to reach toward. One-time
     *  cost per bolt (cached, see {@link #targetsFor}), not per frame/branch, so this can afford to be
     *  generous. */
    private static final float ROD_SEEK_RADIUS = 12.0F;

    /** Attraction hierarchy for branch direction, highest to lowest: a copper rod, then a living entity
     *  (mob/player -- this mod's own fiction already has living things as better conductors than blocks,
     *  see the tesla coil design), then a tree. Each competes as its own candidate direction in
     *  {@link #drawBranches}, weighted by these multipliers against its measured clearance -- makes a
     *  branch noticeably more likely to reach for whichever target is present and highest in the
     *  hierarchy, without making it a certainty; a direction with genuinely more open room can still win,
     *  and with no targets at all a branch just reaches for open air same as before. */
    private static final float ROD_MAGNETISM = 1.6F;
    private static final float LIVING_MAGNETISM = 1.35F;
    private static final float TREE_MAGNETISM = 1.15F;

    /** How far out (blocks) to look for a tree (any log block) for branches to reach toward, same
     *  one-time-per-bolt cost as the rod search. */
    private static final float TREE_SEEK_RADIUS = 12.0F;

    /** Horizontal search radius for mobs/players branches should visibly react to, same as blocks.
     *  Vertical range is the full sky-origin height, since branches can occur anywhere along it.
     *  Padded past SKY_RADIUS: the channel's sky end can itself land up to SKY_RADIUS off the strike's
     *  own X/Z, and branches near that end reach a bit further out again. */
    private static final float ENTITY_SEEK_RADIUS = SKY_RADIUS + 10.0F;

    /**
     * What a bolt's branches should visually reach for or stop at, beyond plain block collision: the
     * nearest copper rod and nearest tree (for the attraction hierarchy) and nearby living entities
     * (mobs, players -- both an attraction target and, so a branch stops at one the same way it stops
     * at a wall, instead of visibly passing straight through). Cosmetic only, per locked decision 2 --
     * the actual strike position is already decided by vanilla before this renderer ever runs.
     */
    private record BranchTargets(BlockPos rod, BlockPos tree, List<AABB> nearbyLiving) {
    }

    /** Computed once per bolt (its first rendered frame) and reused for the rest of its life --
     *  querying the world for a rod and nearby entities is real cost, and doesn't need doing every
     *  frame for a ~0.5s effect. Cleared alongside {@link BoltFlashLight#clear}. */
    private static final Map<Integer, BranchTargets> TARGET_CACHE = new ConcurrentHashMap<>();

    /** Absolute Y the sky origin sits at: the real cloud layer for a typical ground-level strike, or
     *  {@link #MIN_HEIGHT_ABOVE_STRIKE} above the strike itself if that's already higher (a tall build),
     *  capped so it never reaches above the level's actual build height. */
    private static float skyOriginY(float strikeY, int maxBuildHeight) {
        float desired = Math.max(strikeY + MIN_HEIGHT_ABOVE_STRIKE, CLOUD_LEVEL);
        return Math.min(desired, maxBuildHeight - 1.0F);
    }

    private static BranchTargets targetsFor(LightningBolt bolt) {
        return TARGET_CACHE.computeIfAbsent(bolt.getId(), id -> {
            Level level = bolt.level();
            BlockPos strike = bolt.blockPosition();
            BlockPos rod = findNearestMatch(level, strike, ROD_SEEK_RADIUS, state -> state.is(Blocks.LIGHTNING_ROD));
            BlockPos tree = findNearestMatch(level, strike, TREE_SEEK_RADIUS, state -> state.is(BlockTags.LOGS));
            int r = (int) ENTITY_SEEK_RADIUS;
            float skyY = skyOriginY(strike.getY(), level.getMaxBuildHeight());
            AABB searchBox = new AABB(
                    strike.getX() - r, strike.getY(), strike.getZ() - r,
                    strike.getX() + 1 + r, skyY, strike.getZ() + 1 + r);
            List<AABB> living = level.getEntitiesOfClass(LivingEntity.class, searchBox)
                    .stream()
                    .map(LivingEntity::getBoundingBox)
                    .toList();
            return new BranchTargets(rod, tree, living);
        });
    }

    private static void clearTargets(int boltId) {
        TARGET_CACHE.remove(boltId);
        CLOUD_OFFSET_CACHE.remove(boltId);
    }

    /** Sentinel for "searched, no qualifying cloud found nearby" -- {@link Map#computeIfAbsent} can't
     *  store a real {@code null}, so a NaN-x marker distinguishes "not found" from "not yet searched"
     *  without re-running the (fairly cheap, but non-trivial) search every frame. */
    private static final Vector3f NO_CLOUD = new Vector3f(Float.NaN, 0.0F, 0.0F);

    private static final Map<Integer, Vector3f> CLOUD_OFFSET_CACHE = new ConcurrentHashMap<>();

    /**
     * Horizontal offset (local, relative to the strike) of an actual cloud puff near the strike, for the
     * sky origin to align with -- see {@link CloudTexture}. Computed once per bolt (cached, same
     * reasoning as {@link #targetsFor}) via a concentric-ring search outward from the strike, closest
     * candidates first, for a point solidly inside a puff (not just its edge). Returns {@link #NO_CLOUD}
     * if nothing qualifies within {@link #CLOUD_SEARCH_RADIUS} -- callers fall back to a plain random
     * offset in that case.
     */
    private static Vector3f cloudOffsetFor(LightningBolt bolt) {
        return CLOUD_OFFSET_CACHE.computeIfAbsent(bolt.getId(), id -> {
            BlockPos strike = bolt.blockPosition();
            // The scroll drift over a bolt's whole ~11-tick life is under half a block (0.03/tick) --
            // negligible, so evaluating once at spawn time rather than re-searching every frame is fine.
            float gameTime = bolt.tickCount;
            double centerX = strike.getX() + 0.5D;
            double centerZ = strike.getZ() + 0.5D;
            for (float r = 0.0F; r <= CLOUD_SEARCH_RADIUS; r += CLOUD_SEARCH_STEP) {
                int ringSamples = r < 0.5F ? 1 : Math.max(6, Mth.ceil(r * 0.6F));
                for (int i = 0; i < ringSamples; i++) {
                    float angle = (float) (i * (Math.PI * 2.0) / ringSamples);
                    double x = centerX + Mth.cos(angle) * r;
                    double z = centerZ + Mth.sin(angle) * r;
                    if (CloudTexture.isCloudCenter(x, z, gameTime)) {
                        return new Vector3f((float) (x - centerX), 0.0F, (float) (z - centerZ));
                    }
                }
            }
            return NO_CLOUD;
        });
    }

    /** Nearest block matching {@code matcher} to {@code center} within {@code radius}, or null. A
     *  one-time cube scan, cached per bolt (see {@link #targetsFor}) rather than run every frame. */
    private static BlockPos findNearestMatch(Level level, BlockPos center, float radius,
                                             java.util.function.Predicate<BlockState> matcher) {
        int r = (int) radius;
        BlockPos best = null;
        double bestDistSq = Double.MAX_VALUE;
        BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    probe.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                    if (!level.isLoaded(probe) || !matcher.test(level.getBlockState(probe))) {
                        continue;
                    }
                    double distSq = probe.distSqr(center);
                    if (distSq < bestDistSq) {
                        bestDistSq = distSq;
                        best = probe.immutable();
                    }
                }
            }
        }
        return best;
    }

    /** Nearest living-entity bounding box's center to {@code fromWorld} (world-space), or null. */
    private static Vector3f nearestLivingCenter(List<AABB> nearbyLiving, Vector3f fromWorld) {
        AABB best = null;
        double bestDistSq = Double.MAX_VALUE;
        for (AABB box : nearbyLiving) {
            double dx = (box.minX + box.maxX) / 2.0 - fromWorld.x;
            double dy = (box.minY + box.maxY) / 2.0 - fromWorld.y;
            double dz = (box.minZ + box.maxZ) / 2.0 - fromWorld.z;
            double distSq = dx * dx + dy * dy + dz * dz;
            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                best = box;
            }
        }
        if (best == null) {
            return null;
        }
        return new Vector3f((float) ((best.minX + best.maxX) / 2.0),
                (float) ((best.minY + best.maxY) / 2.0), (float) ((best.minZ + best.maxZ) / 2.0));
    }

    private BoltRenderer() {
    }

    /**
     * Draw our bolt instead of vanilla's for the given strike.
     *
     * @return true if we handled it and vanilla's render should be cancelled.
     */
    public static boolean tryRender(LightningBolt bolt, float partialTick, PoseStack poseStack, MultiBufferSource buffers) {
        Level level = bolt.level();
        BlockPos strike = bolt.blockPosition();

        // Defer entirely to Sunwell for its own lamp-triggered strikes (see SunwellCompat) -- draw
        // nothing, don't cancel vanilla's render, so Sunwell's own render-cancel mixin (an independent
        // @Inject, not competing for anything) is free to draw its short lamp-to-ceiling bolt undisturbed
        // instead of it getting stomped by our full sky-to-ground one.
        if (SunwellCompat.isSunwellBolt(level, strike)) {
            return false;
        }

        // Seeded once per bolt id, drawn from in a fixed order every frame so the shape (including the
        // sky-origin offset just below) holds still across the bolt's whole life instead of jittering.
        RandomSource random = RandomSource.create(bolt.getId() * 31L);

        // Sky origin: at the real cloud layer's height (see skyOriginY) so it visually originates from
        // where clouds actually render, not an arbitrary offset -- and horizontally, aligned with an
        // actual rendered cloud puff (see cloudOffsetFor/CloudTexture) when one's found nearby, rather
        // than a point that might land in a gap between clouds. Falls back to a random point within
        // SKY_RADIUS blocks of the strike (updated locked decision 3 -- was a straight vertical drop,
        // same X/Z as the strike; now a genuine long reach across the sky) if no cloud qualifies nearby.
        int maxBuildHeight = level.getMaxBuildHeight();
        float boltY = (float) bolt.getY();
        float h = Math.max(1.0F, skyOriginY(boltY, maxBuildHeight) - boltY);
        float originAngle = random.nextFloat() * (float) (Math.PI * 2.0);
        float originDist = random.nextFloat() * SKY_RADIUS;
        Vector3f cloudOffset = cloudOffsetFor(bolt);
        float offsetX = Float.isNaN(cloudOffset.x) ? Mth.cos(originAngle) * originDist : cloudOffset.x;
        float offsetZ = Float.isNaN(cloudOffset.x) ? Mth.sin(originAngle) * originDist : cloudOffset.z;
        Vector3f top = new Vector3f(offsetX, h, offsetZ);
        Vector3f bottom = new Vector3f(0.0F, 0.0F, 0.0F); // strike = bolt's own position (local origin)
        // Branches scale off this rather than fixed block counts (see drawBranches) -- the trunk's
        // actual length now varies a lot more than it used to (skyOriginY targets the real cloud layer,
        // not a fixed 80-block offset), so a fixed branch length reads as tiny on a much taller trunk.
        float trunkLength = new Vector3f(top).sub(bottom).length();

        float age = bolt.tickCount + partialTick;
        float t = age / LIFE_TICKS;
        if (t >= 1.0F) {
            BoltFlashLight.clear(bolt.getId()); // spent -- release its in-world light immediately
            clearTargets(bolt.getId());
            return true; // ours, but spent -- draw nothing rather than let vanilla back in
        }
        float flick = 0.7F + 0.3F * Mth.abs(Mth.sin(age * 15.0F));

        // Three beats. LEADER: a dim channel creeps down from the sky, searching for the ground.
        // RETURN STROKE: the instant it connects, a brilliant pulse sweeps back UP from the hit point
        // toward the sky -- the bright flash real lightning fires along the leader once it grounds. FADE.
        float reach;
        float baseA;
        float branchBright;
        float skyGlow;
        float cloudGlow; // the storm cloud itself lighting up around the sky origin -- see below
        float impactLight; // strike-point flash: 0 until it lands, peaks on the return stroke, fades after
        float flashRadius; // how far the in-world BLOCK light reaches -- room-wide on the strike tick itself
        float closeFrac;
        float pulseC = 0.0F;
        float pulseW = 0.16F;
        float pulseA = 0.0F;
        if (t < LEADER_END) {
            // Ease-in: slow growth right after leaving the sky origin, accelerating as it nears the
            // ground, instead of a constant linear crawl the whole leader phase.
            float u = Mth.clamp(t / LEADER_END, 0.0F, 1.0F);
            reach = (float) Math.pow(u, LEADER_EASE_POWER);
            baseA = 0.30F * flick;
            // Almost the same brightness as the trunk itself while growing -- real lightning has
            // several candidate "stepped leaders" advancing at once, similarly dim, and you can't tell
            // which one will actually connect until it does. Only the trunk gets the big flash below,
            // at the moment it strikes.
            branchBright = baseA * 0.95F;
            skyGlow = 0.12F + reach * 0.55F;
            // Not tied to `reach`/`u` -- present at full strength from the very first rendered frame,
            // before the leader has visibly grown at all, so the cloud reads as already lit up/charging
            // and the bolt emerges from it, instead of the light appearing to originate from the (still
            // nearly invisible) leader tip. Matches real lightning photography, where the cloud around
            // the strike point is lit up brighter and far wider than the channel itself.
            cloudGlow = 0.5F * flick;
            impactLight = 0.0F;
            flashRadius = 8.0F;
            closeFrac = 0.0F;
        } else if (t < RETURN_END) {
            float u = (t - LEADER_END) / (RETURN_END - LEADER_END);
            reach = 1.0F;
            baseA = 0.5F;
            pulseC = 1.0F - u; // frac 1 = strike, frac 0 = sky: the band runs hit -> sky
            pulseA = 2.2F;
            // Branches stay visible and fully grown through the strike -- real lightning's whole branch
            // structure finishes forming BEFORE the return stroke fires, so a photo taken at the flash
            // shows the complete tree, not just the trunk. Only the trunk gets the extra flash below
            // (pulseA), on top of this same baseline brightness -- that's the reveal: every candidate
            // channel looked the same while growing, and now it's obvious which one struck, without the
            // others vanishing.
            branchBright = baseA * 0.95F;
            skyGlow = 1.0F;
            cloudGlow = 0.7F; // brightens with the return-stroke flash
            impactLight = 1.0F;
            flashRadius = 24.0F;
            closeFrac = 0.0F;
        } else {
            float f = 1.0F - (t - RETURN_END) / (1.0F - RETURN_END);
            reach = 1.0F;
            baseA = 0.6F * f * flick;
            branchBright = baseA * 0.85F;
            skyGlow = baseA;
            cloudGlow = 0.5F * f;
            impactLight = f;
            closeFrac = 1.0F - f;
            flashRadius = 8.0F;
        }

        BoltFlashLight.update(bolt.getId(), strike, impactLight, flashRadius);

        VertexConsumer buffer = buffers.getBuffer(GlowRenderType.BOLT_GLOW);
        Matrix4f matrix = poseStack.last().pose();
        Vector3f camera = new Vector3f(
                (float) (Minecraft.getInstance().gameRenderer.getMainCamera().getPosition().x - bolt.getX()),
                (float) (Minecraft.getInstance().gameRenderer.getMainCamera().getPosition().y - bolt.getY()),
                (float) (Minecraft.getInstance().gameRenderer.getMainCamera().getPosition().z - bolt.getZ()));

        Vector3f[] path = buildPath(random, top, bottom);
        Vector3f[] sides = computeSides(path, camera);

        drawChannel(buffer, matrix, path, sides, BLOOM_WIDTH, 0.6F, 0.72F, 1.0F, reach, 0.14F * baseA, pulseC, pulseW, 1.1F * pulseA, closeFrac);
        drawChannel(buffer, matrix, path, sides, HALO_WIDTH, 0.7F, 0.82F, 1.0F, reach, 0.32F * baseA, pulseC, pulseW, 1.6F * pulseA, closeFrac);
        drawChannel(buffer, matrix, path, sides, CORE_WIDTH, 0.97F, 0.98F, 1.0F, reach, baseA * 1.3F, pulseC, pulseW, pulseA * 1.8F, closeFrac);
        drawChannel(buffer, matrix, path, sides, CORE_HOT_WIDTH, 1.0F, 1.0F, 1.0F, reach, baseA * 1.7F, pulseC, pulseW, pulseA * 2.2F, closeFrac);

        // Occasional major fork(s): channels nearly as bright and thick as the trunk itself, growing
        // alongside it -- real lightning often has more than one candidate "stepped leader" advancing at
        // once, and photos routinely show more than one prominent branch, not just the one dominant
        // trunk. Each of MAJOR_SPLIT_SLOTS rolls independently, so a bolt can have zero, one, or several.
        // Stay visible and fully grown through the strike and fade, same as ordinary branches (see
        // drawBranches) and for the same reason: by the time of the return stroke, real lightning's whole
        // branch structure (major and minor) has already finished forming -- a photo taken at the flash
        // shows the complete tree. They just never get the trunk's own extra flash (splitA tracks baseA,
        // which already brightens per phase same as the trunk's own baseA does, without pulseA on top).
        for (int s = 0; s < MAJOR_SPLIT_SLOTS; s++) {
            if (random.nextFloat() >= MAJOR_SPLIT_CHANCE) {
                continue;
            }
            float splitOriginFrac = 0.15F + random.nextFloat() * 0.4F;
            int splitIdx = Mth.clamp(Math.round(splitOriginFrac * (path.length - 1)), 0, path.length - 1);
            float splitAngle = random.nextFloat() * (float) (Math.PI * 2.0);
            float splitDist = 3.0F + random.nextFloat() * 7.0F;
            // Lands a few blocks from the actual strike, not on top of it -- a visibly different
            // candidate path, not a duplicate of the winning one (or of any other split this bolt has).
            Vector3f splitBottom = new Vector3f(
                    bottom.x + Mth.cos(splitAngle) * splitDist,
                    1.0F + random.nextFloat() * 2.0F,
                    bottom.z + Mth.sin(splitAngle) * splitDist);
            Vector3f[] splitPath = buildPath(random, path[splitIdx], splitBottom);
            Vector3f[] splitSides = computeSides(splitPath, camera);
            // Own growth, relative to where this channel starts along the trunk, so it doesn't finish
            // growing before the trunk's own leader even reaches its origin point.
            float splitReach = Mth.clamp((reach - splitOriginFrac) / (1.0F - splitOriginFrac), 0.0F, 1.0F);
            float splitA = baseA * 0.85F;
            drawChannel(buffer, matrix, splitPath, splitSides, HALO_WIDTH * 0.85F, 0.7F, 0.82F, 1.0F, splitReach, 0.32F * splitA, 0.0F, pulseW, 0.0F, closeFrac);
            drawChannel(buffer, matrix, splitPath, splitSides, CORE_WIDTH * 0.85F, 0.97F, 0.98F, 1.0F, splitReach, splitA * 1.3F, 0.0F, pulseW, 0.0F, closeFrac);
        }

        if (pulseA > 0.0F) {
            // 2x Sunwell's original 1.7/0.75.
            int pulseIdx = Mth.clamp(Math.round(pulseC * (path.length - 1)), 0, path.length - 1);
            Vector3f pulsePos = path[pulseIdx];
            drawGlow(buffer, matrix, camera, pulsePos, 3.4F, 0.55F, 0.85F, 0.92F, 1.0F);
            drawGlow(buffer, matrix, camera, pulsePos, 1.5F, 1.0F, 1.0F, 1.0F, 1.0F);
        }

        if (branchBright > 0.05F) {
            Vector3f boltWorldPos = new Vector3f((float) bolt.getX(), (float) bolt.getY(), (float) bolt.getZ());
            BranchTargets targets = targetsFor(bolt);
            drawBranches(buffer, matrix, camera, random, path, branchBright, reach, level, boltWorldPos, closeFrac, targets, trunkLength);
        }

        // Cloud glow: a large, soft, violet-tinted halo around the sky origin, much bigger and dimmer
        // than the tight sky-end glow below -- the storm cloud itself lighting up, not the bolt's own
        // point-source flare. Drawn first so the tighter, brighter glow reads as sitting inside it.
        // Sized to actually read as an illuminated patch of cloud (radii in blocks) now that the origin
        // sits at the real cloud layer (skyOriginY) instead of a much closer, arbitrary height.
        drawGlow(buffer, matrix, camera, path[0], 22.0F, cloudGlow * 0.45F, 0.55F, 0.6F, 0.9F);
        drawGlow(buffer, matrix, camera, path[0], 11.0F, cloudGlow * 0.75F, 0.7F, 0.75F, 0.95F);

        // Sky-end glow: brightens as the leader charges down, flares at the strike, then fades.
        // 2x Sunwell's original 0.55/0.26.
        drawGlow(buffer, matrix, camera, path[0], 1.1F, skyGlow * 0.45F, 0.7F, 0.82F, 1.0F);
        drawGlow(buffer, matrix, camera, path[0], 0.52F, skyGlow, 0.96F, 0.98F, 1.0F);

        if (impactLight > 0.02F) {
            // Strike-point bloom: 2x Sunwell's original 2.2/1.1/0.45. This is the single biggest, most
            // important flash in the whole VFX (the moment it actually lands) and needs to read from far
            // outside the immediate strike area, not just be a bright dot.
            Vector3f strikePoint = path[path.length - 1];
            drawGlow(buffer, matrix, camera, strikePoint, 4.4F, impactLight * 0.35F, 0.65F, 0.78F, 1.0F);
            drawGlow(buffer, matrix, camera, strikePoint, 2.2F, impactLight * 0.8F, 0.8F, 0.88F, 1.0F);
            drawGlow(buffer, matrix, camera, strikePoint, 0.9F, impactLight * 1.4F, 1.0F, 1.0F, 1.0F);
        }
        return true;
    }

    /**
     * The single channel, as a jagged, bowed polyline from the sky origin ({@code path[0]}) to the
     * strike ({@code path[last]}). Both ends are exact; the wander in between is fixed by
     * {@code random}, seeded from the entity id, so the shape holds still for the bolt's whole life.
     */
    private static Vector3f[] buildPath(RandomSource random, Vector3f top, Vector3f bottom) {
        Vector3f delta = new Vector3f(bottom).sub(top);
        float totalLen = delta.length();
        int steps = Math.max(4, Mth.ceil(totalLen / SEGMENT_LENGTH));
        float reach = Mth.clamp(totalLen * BOW_FRACTION, BOW_MIN, BOW_MAX);

        Vector3f ctrlA = randomBow(random, reach);
        Vector3f ctrlB = randomBow(random, reach);

        Vector3f[] pts = new Vector3f[steps + 1];
        for (int i = 0; i <= steps; i++) {
            float t = (float) i / steps;
            Vector3f p = new Vector3f(top).lerp(bottom, t);
            float wA = envelope(t, 0.33F);
            float wB = envelope(t, 0.66F);
            p.add(ctrlA.x * wA + ctrlB.x * wB, ctrlA.y * wA + ctrlB.y * wB, ctrlA.z * wA + ctrlB.z * wB);
            float jag = t * (1.0F - t) * 4.0F; // 0 at both anchors
            p.add((random.nextFloat() - 0.5F) * JAG * 2.0F * jag,
                    (random.nextFloat() - 0.5F) * JAG * jag,
                    (random.nextFloat() - 0.5F) * JAG * 2.0F * jag);
            pts[i] = p;
        }
        return pts;
    }

    private static Vector3f[] computeSides(Vector3f[] pts, Vector3f camera) {
        int n = pts.length;
        Vector3f[] sides = new Vector3f[n];
        for (int i = 0; i < n; i++) {
            Vector3f tan = new Vector3f();
            if (i > 0) {
                Vector3f d = new Vector3f(pts[i]).sub(pts[i - 1]);
                if (d.lengthSquared() > 1.0E-8F) {
                    tan.add(d.normalize());
                }
            }
            if (i < n - 1) {
                Vector3f d = new Vector3f(pts[i + 1]).sub(pts[i]);
                if (d.lengthSquared() > 1.0E-8F) {
                    tan.add(d.normalize());
                }
            }
            if (tan.lengthSquared() < 1.0E-8F) {
                tan.set(0.0F, 1.0F, 0.0F);
            }
            Vector3f toCam = new Vector3f(camera).sub(pts[i]);
            Vector3f side = new Vector3f(tan).cross(toCam);
            if (side.lengthSquared() < 1.0E-8F) {
                side.set(1.0F, 0.0F, 0.0F);
            }
            sides[i] = side.normalize();
        }
        return sides;
    }

    private static void vtx(VertexConsumer buffer, Matrix4f m, Vector3f p, Vector3f side, float k,
                            float cr, float cg, float cb) {
        buffer.addVertex(m, p.x + side.x * k, p.y + side.y * k, p.z + side.z * k).setColor(cr, cg, cb, 1.0F);
    }

    /** A soft camera-facing bloom at a point -- used to flare the sky end as the leader charges. */
    private static void drawGlow(VertexConsumer buffer, Matrix4f matrix, Vector3f camera, Vector3f pos,
                                 float radius, float alpha, float r, float g, float b) {
        if (alpha <= 0.004F) {
            return;
        }
        Vector3f toCam = new Vector3f(camera).sub(pos);
        if (toCam.lengthSquared() < 1.0E-6F) {
            return;
        }
        toCam.normalize();
        Vector3f up = Math.abs(toCam.y) > 0.99F ? new Vector3f(1.0F, 0.0F, 0.0F) : new Vector3f(0.0F, 1.0F, 0.0F);
        Vector3f rx = new Vector3f(up).cross(toCam).normalize().mul(radius);
        Vector3f ry = new Vector3f(toCam).cross(rx).normalize().mul(radius);
        float cr = r * alpha;
        float cg = g * alpha;
        float cb = b * alpha;
        int seg = 14;
        Vector3f prev = new Vector3f(pos).add(rx);
        for (int i = 1; i <= seg; i++) {
            double a = i * (Math.PI * 2.0) / seg;
            float cos = (float) Math.cos(a);
            float sin = (float) Math.sin(a);
            Vector3f cur = new Vector3f(pos).add(
                    rx.x * cos + ry.x * sin, rx.y * cos + ry.y * sin, rx.z * cos + ry.z * sin);
            buffer.addVertex(matrix, pos.x, pos.y, pos.z).setColor(cr, cg, cb, 1.0F);
            buffer.addVertex(matrix, prev.x, prev.y, prev.z).setColor(0.0F, 0.0F, 0.0F, 1.0F);
            buffer.addVertex(matrix, cur.x, cur.y, cur.z).setColor(0.0F, 0.0F, 0.0F, 1.0F);
            buffer.addVertex(matrix, pos.x, pos.y, pos.z).setColor(cr, cg, cb, 1.0F);
            prev = cur;
        }
    }

    private static void drawChannel(VertexConsumer buffer, Matrix4f matrix, Vector3f[] pts, Vector3f[] sides,
                                    float halfWidth, float r, float g, float b,
                                    float reach, float baseAlpha, float pulseC, float pulseW, float pulseAlpha) {
        drawChannel(buffer, matrix, pts, sides, halfWidth, r, g, b, reach, baseAlpha, pulseC, pulseW, pulseAlpha, 0.0F);
    }

    /** @param tipFade 0 = uniform; &gt;0 fades alpha toward the far end (branches: brighter near the shaft). */
    private static void drawChannel(VertexConsumer buffer, Matrix4f matrix, Vector3f[] pts, Vector3f[] sides,
                                    float halfWidth, float r, float g, float b,
                                    float reach, float baseAlpha, float pulseC, float pulseW, float pulseAlpha,
                                    float tipFade) {
        int last = pts.length - 1;
        if (last < 1) {
            return;
        }
        for (int i = 1; i <= last; i++) {
            float frac = (float) i / last;
            if (frac > reach) {
                break; // leader hasn't grown this far yet
            }
            float a = baseAlpha;
            if (pulseAlpha > 0.0F) {
                float d = frac - pulseC;
                a += pulseAlpha * (float) Math.exp(-(d * d) / (2.0F * pulseW * pulseW));
            }
            float hw = halfWidth;
            if (tipFade > 0.0F) {
                a *= 1.0F - tipFade * frac;
                hw *= 1.0F - tipFade * 0.55F * frac;
            }
            if (a <= 0.004F) {
                continue;
            }
            float cr = r * a;
            float cg = g * a;
            float cb = b * a;
            vtx(buffer, matrix, pts[i - 1], sides[i - 1], -hw, cr, cg, cb);
            vtx(buffer, matrix, pts[i], sides[i], -hw, cr, cg, cb);
            vtx(buffer, matrix, pts[i], sides[i], hw, cr, cg, cb);
            vtx(buffer, matrix, pts[i - 1], sides[i - 1], hw, cr, cg, cb);
        }
    }

    /** How much a branch is allowed to shorten to stop right at a surface instead of clipping through it. */
    private static final float REACH_MARGIN = 0.25F;

    private static final float BRANCH_GROWTH = 0.35F;
    // Recalibrated for the shorter branch lengths below (real-lightning-reference correction -- see
    // drawBranches): roughly Sunwell's original 2.0/1.0 scaled ~1.5x for our bigger trunk, not the 6x
    // values a previous "make branches longer" pass left here, which were tuned for branches nearly half
    // the trunk's own length.
    private static final float BRANCH_LEN_REFERENCE = 3.0F;
    private static final float SUB_LEN_REFERENCE = 1.5F;
    private static final float BRANCH_GROWTH_MIN = 0.12F;
    private static final float BRANCH_GROWTH_MAX = 0.9F;

    /**
     * Forks off the main channel: several primary branches from the upper three-quarters, each
     * spawning its own smaller sub-branches, all angling down and out and fading. World-aware: each
     * primary samples a couple of nearby directions, plus one candidate each toward a nearby rod, living
     * entity, and tree if present -- an attraction hierarchy weighted {@link #ROD_MAGNETISM} &gt;
     * {@link #LIVING_MAGNETISM} &gt; {@link #TREE_MAGNETISM} (highest wins the comparison when several
     * are in range, none of it forced) -- and keeps whichever candidate has the most open room ahead of
     * it, then its length is clipped to stop right where it actually reaches a block or a nearby living
     * entity. Surface-seeking flavor per locked decision 2, cosmetic only, not real targeting.
     *
     * <p>Length and angle tuned against real lightning photography: one dominant bright trunk with many
     * short, comparatively thin offshoots fanning widely away from it, not a handful of long channels
     * running near-parallel to the trunk for most of its length (which is what branches noticeably
     * longer than roughly 10-15% of the trunk's own length, with a strong bias to keep going in the
     * trunk's own direction, actually reads as).</p>
     *
     * <p>Brightness beat (set by the caller via {@code bright}): almost the same as the trunk while
     * growing -- so it's genuinely ambiguous which channel will connect -- and never lit during the
     * return-stroke flash, since only the trunk (structurally the one that reaches the actual strike
     * point) does. Branches simply aren't drawn at all during that beat ({@code bright} is 0).</p>
     */
    private static void drawBranches(VertexConsumer buffer, Matrix4f matrix, Vector3f camera, RandomSource random,
                                     Vector3f[] path, float bright, float reach, Level level, Vector3f worldOrigin,
                                     float closeFrac, BranchTargets targets, float trunkLength) {
        int last = path.length - 1;
        int count = 14 + random.nextInt(10); // 14-23 primaries -- bushier, closer to real photo density
        for (int i = 0; i < count; i++) {
            float originFrac = 0.12F + random.nextFloat() * 0.78F;
            int oi = Mth.clamp(Math.round(originFrac * last), 0, last);
            Vector3f originWorld = new Vector3f(worldOrigin).add(path[oi]);
            Vector3f tan = new Vector3f(path[Math.min(oi + 1, last)]).sub(path[Math.max(oi - 1, 0)]);
            if (tan.lengthSquared() < 1.0E-6F) {
                tan.set(0.0F, -1.0F, 0.0F);
            }
            tan.normalize();
            Vector3f ref = Math.abs(tan.y) > 0.9F ? new Vector3f(1.0F, 0.0F, 0.0F) : new Vector3f(0.0F, 1.0F, 0.0F);
            Vector3f perpA = new Vector3f(tan).cross(ref).normalize();
            Vector3f perpB = new Vector3f(tan).cross(perpA).normalize();
            float ang = random.nextFloat() * (float) (Math.PI * 2.0);
            Vector3f out = new Vector3f(perpA).mul((float) Math.cos(ang)).add(perpB.mul((float) Math.sin(ang)));
            // Low `forward` (little continuation in the trunk's own direction) and a wide `spread` --
            // real branches fork sharply away from the trunk, they don't run alongside it.
            float forward = 0.15F + random.nextFloat() * 0.3F;
            float spread = 1.0F + random.nextFloat() * 0.8F;
            Vector3f dir = new Vector3f(tan).mul(forward).add(out.mul(spread));
            dir.add(0.0F, -0.3F, 0.0F).normalize();
            float topFactor = 1.0F - originFrac;
            // Scaled off the trunk's own actual length (roughly 6-14%, matching real photo proportions)
            // rather than a fixed block count -- the trunk's length now varies a lot more than it used
            // to (skyOriginY targets the real cloud layer, not a fixed 80-block offset), so a fixed
            // length reads as tiny on a much taller trunk.
            float len = trunkLength * (0.06F + random.nextFloat() * 0.08F) * (0.5F + topFactor * 0.9F);
            int branchDepth = topFactor > 0.6F ? 3 : (topFactor > 0.35F ? 2 : (topFactor > 0.15F ? 1 : 0));

            Vector3f bestDir = dir;
            float bestClear = clearDistance(level, worldOrigin, path[oi], dir, len, targets.nearbyLiving());
            for (int c = 0; c < 2; c++) {
                float jitterAng = ang + (random.nextFloat() - 0.5F) * 1.6F;
                Vector3f jOut = new Vector3f(perpA).mul((float) Math.cos(jitterAng)).add(perpB.mul((float) Math.sin(jitterAng)));
                Vector3f jDir = new Vector3f(tan).mul(forward).add(jOut.mul(spread));
                jDir.add(0.0F, -0.3F, 0.0F).normalize();
                float clear = clearDistance(level, worldOrigin, path[oi], jDir, len, targets.nearbyLiving());
                if (clear > bestClear) {
                    bestClear = clear;
                    bestDir = jDir;
                }
            }
            // Attraction hierarchy: rod, then living entity, then tree, each its own candidate direction
            // (not restricted to the jitter arc above, so a branch can genuinely reach across toward one
            // rather than only wobbling near its original forking angle), weighted so higher-priority
            // targets tend to win when several are in range without ever being forced.
            if (targets.rod() != null) {
                Vector3f rodDir = new Vector3f(
                        (float) (targets.rod().getX() + 0.5D) - originWorld.x,
                        (float) (targets.rod().getY() + 0.5D) - originWorld.y,
                        (float) (targets.rod().getZ() + 0.5D) - originWorld.z);
                if (rodDir.lengthSquared() > 1.0E-6F) {
                    rodDir.normalize();
                    float rodClear = clearDistance(level, worldOrigin, path[oi], rodDir, len, targets.nearbyLiving());
                    if (rodClear * ROD_MAGNETISM > bestClear) {
                        bestClear = rodClear;
                        bestDir = rodDir;
                    }
                }
            }
            Vector3f livingCenter = nearestLivingCenter(targets.nearbyLiving(), originWorld);
            if (livingCenter != null) {
                Vector3f livingDir = new Vector3f(livingCenter).sub(originWorld);
                if (livingDir.lengthSquared() > 1.0E-6F) {
                    livingDir.normalize();
                    float livingClear = clearDistance(level, worldOrigin, path[oi], livingDir, len, targets.nearbyLiving());
                    if (livingClear * LIVING_MAGNETISM > bestClear) {
                        bestClear = livingClear;
                        bestDir = livingDir;
                    }
                }
            }
            if (targets.tree() != null) {
                Vector3f treeDir = new Vector3f(
                        (float) (targets.tree().getX() + 0.5D) - originWorld.x,
                        (float) (targets.tree().getY() + 0.5D) - originWorld.y,
                        (float) (targets.tree().getZ() + 0.5D) - originWorld.z);
                if (treeDir.lengthSquared() > 1.0E-6F) {
                    treeDir.normalize();
                    float treeClear = clearDistance(level, worldOrigin, path[oi], treeDir, len, targets.nearbyLiving());
                    if (treeClear * TREE_MAGNETISM > bestClear) {
                        bestClear = treeClear;
                        bestDir = treeDir;
                    }
                }
            }
            dir = bestDir;
            len = Math.min(bestClear, len);

            float growth = Mth.clamp((reach - originFrac) / branchGrowthWindow(len), 0.0F, 1.0F);
            // Noticeably thinner than the trunk's own core (was 0.65-1.25x CORE_WIDTH) -- real branches
            // read as wispy compared to the bright dominant channel, not near-equal in thickness.
            float width = CORE_WIDTH * (0.3F + random.nextFloat() * 0.35F);
            float branchCloseMul = Mth.clamp(1.0F - closeFrac * originFrac * 1.4F, 0.0F, 1.0F);
            drawFork(buffer, matrix, camera, random, path[oi], dir, len, width, bright * branchCloseMul,
                    branchDepth, growth, level, worldOrigin, targets.nearbyLiving());
        }
    }

    private static void drawFork(VertexConsumer buffer, Matrix4f matrix, Vector3f camera, RandomSource random,
                                 Vector3f from, Vector3f dir, float length, float width, float bright, int depth,
                                 float growth, Level level, Vector3f worldOrigin, List<AABB> nearbyLiving) {
        int steps = Math.max(2, Mth.ceil(length / SEGMENT_LENGTH));
        Vector3f end = new Vector3f(from).add(new Vector3f(dir).mul(length));
        Vector3f[] pts = new Vector3f[steps + 1];
        pts[0] = new Vector3f(from);
        float jag = Mth.clamp(length * 0.09F, JAG * 0.9F, JAG * 2.8F);
        for (int i = 1; i <= steps; i++) {
            float t = (float) i / steps;
            Vector3f pnt = new Vector3f(from).lerp(end, t);
            pnt.add((random.nextFloat() - 0.5F) * jag * 1.5F * t,
                    (random.nextFloat() - 0.5F) * jag * t,
                    (random.nextFloat() - 0.5F) * jag * 1.5F * t);
            pts[i] = pnt;
        }
        if (growth > 0.0F && bright > 0.02F && length >= 0.35F) {
            Vector3f[] sides = computeSides(pts, camera);
            drawChannel(buffer, matrix, pts, sides, width * 1.8F, 0.5F, 0.62F, 1.0F, growth, bright * 0.3F, 0.0F, 0.1F, 0.0F, 0.55F);
            drawChannel(buffer, matrix, pts, sides, width, 0.9F, 0.94F, 1.0F, growth, bright, 0.0F, 0.1F, 0.0F, 0.55F);
        }
        if (depth > 0) {
            int subs = random.nextInt(4); // 0-3 per level (was 0-2) -- denser sub-forking
            for (int k = 0; k < subs; k++) {
                Vector3f origin = pts[Math.min(1 + random.nextInt(steps), steps)];
                Vector3f sub = new Vector3f(dir).add(
                        (random.nextFloat() - 0.5F) * 0.8F,
                        -0.15F - 0.2F * random.nextFloat(),
                        (random.nextFloat() - 0.5F) * 0.8F).normalize();
                float rolledSubLen = length * (0.35F + 0.3F * random.nextFloat());
                float subLen = Math.min(clearDistance(level, worldOrigin, origin, sub, rolledSubLen, nearbyLiving), rolledSubLen);
                float subWindow = Mth.clamp(0.65F * (SUB_LEN_REFERENCE / Math.max(subLen, 0.3F)),
                        BRANCH_GROWTH_MIN, BRANCH_GROWTH_MAX);
                float subGrowth = Mth.clamp((growth - (1.0F - subWindow)) / subWindow, 0.0F, 1.0F);
                drawFork(buffer, matrix, camera, random, origin, sub,
                        subLen, width * 0.62F, bright * 0.6F, depth - 1, subGrowth, level, worldOrigin, nearbyLiving);
            }
        }
    }

    /**
     * How far (in blocks) a ray from LOCAL point {@code from} along {@code dir} can travel before
     * hitting a solid block OR one of {@code nearbyLiving}'s bounding boxes (a mob or player -- so a
     * branch visibly stops at them instead of passing straight through, the same way it already stops
     * at a wall or a tree), capped at {@code max}. Fixed-step sampling -- cheap and good enough for a
     * decorative, once-per-branch query, not exact voxel/entity traversal.
     */
    private static float clearDistance(Level level, Vector3f worldOrigin, Vector3f from, Vector3f dir, float max,
                                       List<AABB> nearbyLiving) {
        float step = 0.4F;
        BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
        for (float d = step; d <= max; d += step) {
            float x = worldOrigin.x + from.x + dir.x * d;
            float y = worldOrigin.y + from.y + dir.y * d;
            float z = worldOrigin.z + from.z + dir.z * d;
            probe.set(Mth.floor(x), Mth.floor(y), Mth.floor(z));
            BlockState state = level.getBlockState(probe);
            if (!state.getCollisionShape(level, probe).isEmpty()) {
                return Math.max(0.0F, d - step - REACH_MARGIN);
            }
            for (int e = 0; e < nearbyLiving.size(); e++) {
                if (nearbyLiving.get(e).contains(x, y, z)) {
                    return Math.max(0.0F, d - step - REACH_MARGIN);
                }
            }
        }
        return max;
    }

    private static float branchGrowthWindow(float len) {
        return Mth.clamp(BRANCH_GROWTH * (BRANCH_LEN_REFERENCE / Math.max(len, 0.4F)),
                BRANCH_GROWTH_MIN, BRANCH_GROWTH_MAX);
    }

    /** A random, mostly-horizontal displacement of up to ~{@code reach} for a bow control point. */
    private static Vector3f randomBow(RandomSource random, float reach) {
        float angle = random.nextFloat() * ((float) Math.PI * 2.0F);
        float mag = reach * (0.4F + random.nextFloat() * 0.9F);
        return new Vector3f(Mth.cos(angle) * mag, (random.nextFloat() - 0.5F) * mag * 0.5F, Mth.sin(angle) * mag);
    }

    /** Triangular weight peaking at {@code peak}, 0 at both ends — keeps anchors pinned. */
    private static float envelope(float t, float peak) {
        return t < peak ? t / peak : (1.0F - t) / (1.0F - peak);
    }

}
