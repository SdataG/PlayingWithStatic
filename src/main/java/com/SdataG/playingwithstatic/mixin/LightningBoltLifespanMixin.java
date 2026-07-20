package com.SdataG.playingwithstatic.mixin;

import com.SdataG.playingwithstatic.integration.SunwellCompat;
import net.minecraft.world.entity.LightningBolt;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Vanilla's own {@code LightningBolt} has no fixed lifespan. Its {@code life}/{@code flashes} fields
 * are plain, unsynchronized ints (confirmed in the real 1.21.1 source -- no {@code SynchedEntityData}
 * involved), so the client and server each roll their own independent {@code RandomSource} for them and
 * can discard the entity at genuinely different ticks. Worked out from the actual retry logic in
 * {@code LightningBolt#tick}: the absolute fastest possible discard is as early as tick 5 (a real,
 * non-negligible case -- roughly 1 in 30 bolts, not a vanishing edge case), which is earlier than this
 * mod's own bolt VFX needs to finish its leader-growth / return-stroke / fade sequence. When the entity
 * disappears first, the leader visibly stops partway down and the whole bolt vanishes without ever
 * reaching the ground.
 *
 * <p>Rather than shrinking our own animation to fit inside that unpredictable window (which was tried
 * and didn't hold up), this holds {@code discard()} off until our animation's own tick budget has
 * elapsed -- on <em>both</em> sides, since either side's own local roll could independently be the one
 * that discards early. Skipped for Sunwell-owned bolts (a separate mod's own timing, not ours to
 * extend).</p>
 *
 * <p>Known minor side effect: the block guarding {@code discard()} re-runs its own (cheap) nearby-entity
 * scan and re-fires an advancement criteria trigger every held tick once {@code flashes} reaches 0 --
 * unavoidable without a more invasive mixin into that logic itself. Harmless in practice: the scan is a
 * small, bounded query, and re-triggering an already-met advancement criterion is a standard no-op in
 * vanilla's advancement system.</p>
 */
@Mixin(LightningBolt.class)
public abstract class LightningBoltLifespanMixin {

    /** Matches {@code BoltRenderer.LIFE_TICKS} (a client-only class -- not referenced directly here so
     *  this mixin, which must apply on the server too, doesn't pull in client-only code). Keep in sync
     *  if that changes. */
    private static final int MIN_LIFESPAN_TICKS = 11;

    /**
     * Target is {@code LightningBolt;discard()V}, NOT {@code Entity;discard()V} where the method is
     * actually declared -- confirmed by disassembling the real compiled class (javap), not assumed. The
     * first attempt at this mixin used the declaring class and silently matched zero targets (masked by
     * {@code require = 0}, so no crash, just a fix that never actually applied) because javac emits
     * {@code this.discard()} here using the compile-time type of {@code this} (LightningBolt), not the
     * class {@code discard()} happens to be declared in.
     */
    @Redirect(
            method = "tick",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LightningBolt;discard()V"),
            require = 0)
    private void playingwithstatic$holdForAnimation(LightningBolt self) {
        if (self.tickCount < MIN_LIFESPAN_TICKS && !SunwellCompat.isSunwellBolt(self.level(), self.blockPosition())) {
            return; // not yet -- let our animation finish growing/striking/fading first
        }
        self.discard();
    }
}
