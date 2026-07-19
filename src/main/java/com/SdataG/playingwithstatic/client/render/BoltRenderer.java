package com.SdataG.playingwithstatic.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Matrix4f;
import org.joml.Vector3f;

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
     *  Fraction/min/max all 2x their first pass. */
    private static final float BOW_FRACTION = 0.30F;
    private static final float BOW_MIN = 0.7F;
    private static final float BOW_MAX = 32.0F;

    /** Thin bright core, soft glow, and a wide luminous bloom (the photo look). Whole effect is 2x
     *  Sunwell's original SunwellBoltRenderer sizing (0.035/0.018/0.13/0.42) -- a sky-to-ground bolt
     *  reads much smaller relative to open-air view distance than Sunwell's short lamp-to-ceiling one
     *  did, so it needs to be visually much thicker to read at all from a normal viewing distance. */
    private static final float CORE_WIDTH = 0.07F;
    /** Inner hyper-bright filament, thinner than the core -- fakes a bloom right at the white-hot centre. */
    private static final float CORE_HOT_WIDTH = 0.036F;
    private static final float HALO_WIDTH = 0.26F;
    private static final float BLOOM_WIDTH = 0.84F;

    /** How far above the strike the leader visually originates from. Capped by the level's actual
     *  build height so it never reaches for a point that doesn't exist (e.g. the Nether). */
    private static final float SKY_HEIGHT_ABOVE = 80.0F;

    /** Whole VFX length in ticks (~half a second at 20 tps) -- locked decision 4, matches Sunwell's
     *  timing so thunder/sound retiming logic stays compatible without rework. */
    private static final float LIFE_TICKS = 10.0F;

    /** Beat boundaries over the 10-tick life: spread (leader) ticks 0-5, strike (return) 5-6.8, fade after. */
    private static final float LEADER_END = 0.5F;
    private static final float RETURN_END = 0.68F;

    /** The tick the return stroke lands on — when the thunder/impact sound should play. */
    public static final int STRIKE_TICK = Math.round(LEADER_END * LIFE_TICKS);

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

        // Sky origin: same X/Z as the strike (locked decision 3), straight up from the bolt's own
        // local position. Capped by the level's real build height so a strike near the world ceiling
        // doesn't reach for a point above it.
        int maxBuildHeight = level.getMaxBuildHeight();
        float boltY = (float) bolt.getY();
        float h = Math.max(1.0F, Math.min(SKY_HEIGHT_ABOVE, maxBuildHeight - boltY));
        Vector3f top = new Vector3f(0.0F, h, 0.0F);
        Vector3f bottom = new Vector3f(0.0F, 0.0F, 0.0F); // strike = bolt's own position (local origin)

        float age = bolt.tickCount + partialTick;
        float t = age / LIFE_TICKS;
        if (t >= 1.0F) {
            BoltFlashLight.clear(bolt.getId()); // spent -- release its in-world light immediately
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
        float impactLight; // strike-point flash: 0 until it lands, peaks on the return stroke, fades after
        float flashRadius; // how far the in-world BLOCK light reaches -- room-wide on the strike tick itself
        float closeFrac;
        float pulseC = 0.0F;
        float pulseW = 0.16F;
        float pulseA = 0.0F;
        if (t < LEADER_END) {
            reach = Mth.clamp(t / LEADER_END, 0.0F, 1.0F);
            baseA = 0.30F * flick;
            branchBright = baseA * 0.9F;
            skyGlow = 0.12F + reach * 0.55F;
            impactLight = 0.0F;
            flashRadius = 8.0F;
            closeFrac = 0.0F;
        } else if (t < RETURN_END) {
            float u = (t - LEADER_END) / (RETURN_END - LEADER_END);
            reach = 1.0F;
            baseA = 0.5F;
            pulseC = 1.0F - u; // frac 1 = strike, frac 0 = sky: the band runs hit -> sky
            pulseA = 2.2F;
            branchBright = 0.0F;
            skyGlow = 1.0F;
            impactLight = 1.0F;
            flashRadius = 24.0F;
            closeFrac = 0.0F;
        } else {
            float f = 1.0F - (t - RETURN_END) / (1.0F - RETURN_END);
            reach = 1.0F;
            baseA = 0.6F * f * flick;
            branchBright = baseA * 0.85F;
            skyGlow = baseA;
            impactLight = f;
            closeFrac = 1.0F - f;
            flashRadius = 8.0F;
        }

        BoltFlashLight.update(bolt.getId(), strike, impactLight, flashRadius);

        RandomSource random = RandomSource.create(bolt.getId() * 31L);
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

        if (pulseA > 0.0F) {
            // 2x Sunwell's original 1.7/0.75.
            int pulseIdx = Mth.clamp(Math.round(pulseC * (path.length - 1)), 0, path.length - 1);
            Vector3f pulsePos = path[pulseIdx];
            drawGlow(buffer, matrix, camera, pulsePos, 3.4F, 0.55F, 0.85F, 0.92F, 1.0F);
            drawGlow(buffer, matrix, camera, pulsePos, 1.5F, 1.0F, 1.0F, 1.0F, 1.0F);
        }

        if (branchBright > 0.05F) {
            Vector3f boltWorldPos = new Vector3f((float) bolt.getX(), (float) bolt.getY(), (float) bolt.getZ());
            drawBranches(buffer, matrix, camera, random, path, branchBright, reach, level, boltWorldPos, closeFrac);
        }

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
    // 2x Sunwell's original reference lengths, so growth-speed pacing stays calibrated the same
    // relative to the now-2x branch lengths below instead of every branch reading as "far away, lash
    // out fast" once lengths doubled but the references classifying near/far didn't.
    private static final float BRANCH_LEN_REFERENCE = 4.0F;
    private static final float SUB_LEN_REFERENCE = 2.0F;
    private static final float BRANCH_GROWTH_MIN = 0.12F;
    private static final float BRANCH_GROWTH_MAX = 0.9F;

    /**
     * Forks off the main channel: several primary branches from the upper three-quarters, each
     * spawning its own smaller sub-branches, all angling down and out and fading. World-aware: each
     * primary samples a couple of nearby directions and keeps whichever has the most open room ahead
     * of it, then its length is clipped to stop right where it actually reaches a block -- surface-
     * seeking flavor per locked decision 2, cosmetic only, not real targeting.
     */
    private static void drawBranches(VertexConsumer buffer, Matrix4f matrix, Vector3f camera, RandomSource random,
                                     Vector3f[] path, float bright, float reach, Level level, Vector3f worldOrigin,
                                     float closeFrac) {
        int last = path.length - 1;
        int count = 4 + random.nextInt(4); // 4-7 primaries, fixed by the seed
        for (int i = 0; i < count; i++) {
            float originFrac = 0.12F + random.nextFloat() * 0.78F;
            int oi = Mth.clamp(Math.round(originFrac * last), 0, last);
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
            float forward = 0.45F + random.nextFloat() * 0.4F;
            float spread = 0.9F + random.nextFloat() * 0.7F;
            Vector3f dir = new Vector3f(tan).mul(forward).add(out.mul(spread));
            dir.add(0.0F, -0.3F, 0.0F).normalize();
            float topFactor = 1.0F - originFrac;
            // 2x Sunwell's original (1.6 + rand*2.4) base length, so branches read proportionally as
            // long as the main channel now reads thick, instead of looking stubby next to a bigger core.
            float len = (3.2F + random.nextFloat() * 4.8F) * (0.65F + topFactor * 1.15F);
            int branchDepth = topFactor > 0.6F ? 3 : (topFactor > 0.35F ? 2 : (topFactor > 0.15F ? 1 : 0));

            Vector3f bestDir = dir;
            float bestClear = clearDistance(level, worldOrigin, path[oi], dir, len);
            for (int c = 0; c < 2; c++) {
                float jitterAng = ang + (random.nextFloat() - 0.5F) * 1.6F;
                Vector3f jOut = new Vector3f(perpA).mul((float) Math.cos(jitterAng)).add(perpB.mul((float) Math.sin(jitterAng)));
                Vector3f jDir = new Vector3f(tan).mul(forward).add(jOut.mul(spread));
                jDir.add(0.0F, -0.3F, 0.0F).normalize();
                float clear = clearDistance(level, worldOrigin, path[oi], jDir, len);
                if (clear > bestClear) {
                    bestClear = clear;
                    bestDir = jDir;
                }
            }
            dir = bestDir;
            len = Math.min(bestClear, len);

            float growth = Mth.clamp((reach - originFrac) / branchGrowthWindow(len), 0.0F, 1.0F);
            float width = CORE_WIDTH * (0.65F + random.nextFloat() * 0.6F);
            float branchCloseMul = Mth.clamp(1.0F - closeFrac * originFrac * 1.4F, 0.0F, 1.0F);
            drawFork(buffer, matrix, camera, random, path[oi], dir, len, width, bright * branchCloseMul,
                    branchDepth, growth, level, worldOrigin);
        }
    }

    private static void drawFork(VertexConsumer buffer, Matrix4f matrix, Vector3f camera, RandomSource random,
                                 Vector3f from, Vector3f dir, float length, float width, float bright, int depth,
                                 float growth, Level level, Vector3f worldOrigin) {
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
            int subs = random.nextInt(3);
            for (int k = 0; k < subs; k++) {
                Vector3f origin = pts[Math.min(1 + random.nextInt(steps), steps)];
                Vector3f sub = new Vector3f(dir).add(
                        (random.nextFloat() - 0.5F) * 0.8F,
                        -0.15F - 0.2F * random.nextFloat(),
                        (random.nextFloat() - 0.5F) * 0.8F).normalize();
                float rolledSubLen = length * (0.35F + 0.3F * random.nextFloat());
                float subLen = Math.min(clearDistance(level, worldOrigin, origin, sub, rolledSubLen), rolledSubLen);
                float subWindow = Mth.clamp(0.65F * (SUB_LEN_REFERENCE / Math.max(subLen, 0.3F)),
                        BRANCH_GROWTH_MIN, BRANCH_GROWTH_MAX);
                float subGrowth = Mth.clamp((growth - (1.0F - subWindow)) / subWindow, 0.0F, 1.0F);
                drawFork(buffer, matrix, camera, random, origin, sub,
                        subLen, width * 0.62F, bright * 0.6F, depth - 1, subGrowth, level, worldOrigin);
            }
        }
    }

    /**
     * How far (in blocks) a ray from LOCAL point {@code from} along {@code dir} can travel before
     * hitting a solid block, capped at {@code max}. Fixed-step sampling -- cheap and good enough for a
     * decorative, once-per-branch query, not exact voxel traversal.
     */
    private static float clearDistance(Level level, Vector3f worldOrigin, Vector3f from, Vector3f dir, float max) {
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
