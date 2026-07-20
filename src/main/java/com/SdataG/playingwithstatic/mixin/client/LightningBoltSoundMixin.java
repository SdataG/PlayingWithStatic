package com.SdataG.playingwithstatic.mixin.client;

import com.SdataG.playingwithstatic.client.render.BoltRenderer;
import com.SdataG.playingwithstatic.integration.SunwellCompat;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Moves every (non-Sunwell) bolt's thunder/impact sound to the moment it actually strikes. Vanilla
 * fires both sounds on the bolt's first tick; our animation spends its first several ticks on the dim
 * leader creeping down, and only lands the bright return stroke at {@link BoltRenderer#STRIKE_TICK}.
 * This mutes vanilla's early sound and plays it on the strike tick instead -- the flash and the crack
 * together. {@link SunwellCompat} keeps this from touching a Sunwell-owned lamp strike's own retimed
 * thunder (see {@link #playingwithstatic$strikeSound}).
 *
 * <p><b>{@code require = 0} on the redirect below, deliberately.</b> Sunwell (this mod's own sibling
 * project) redirects the exact same {@code Level.playLocalSound} call on the exact same
 * {@code LightningBolt.tick} for the identical reason (retiming thunder to its own strike moment).
 * Sponge Mixin's {@code @Redirect} claims a specific bytecode instruction -- only one mod's redirect can
 * physically win it, and whichever loses finds zero remaining call sites. With the default
 * {@code require = 1} that's a hard crash at boot (confirmed: this exact crash, with both mods installed
 * in the same instance). {@code require = 0} makes a lost race a silent no-op instead of a crash.</p>
 *
 * <p>Muting is left unconditional (not gated on {@link SunwellCompat}) even though the redirect can lose
 * the race: Sunwell doesn't care <em>who</em> muted vanilla's early sound, only that it's muted, so if
 * ours wins there's no harm in muting Sunwell's bolts too -- {@link #playingwithstatic$strikeSound}
 * below is what actually avoids double-playing over Sunwell's own retimed thunder. The one case this
 * doesn't fully close: if Sunwell's redirect wins the race instead of ours, vanilla's early sound plays
 * unmuted for genuine sky strikes (Sunwell only mutes its own), and our own strike-tick thunder still
 * plays on top -- occasional doubled thunder for sky strikes specifically, not lamp ones. Deterministically
 * winning the race (e.g. a lower Mixin {@code priority}) isn't safe to force from this side alone:
 * Sunwell's own redirect doesn't have {@code require = 0}, so forcing it to always lose would move this
 * exact crash onto Sunwell instead of fixing it. Closing this fully needs a change on Sunwell's side too
 * (see ROADMAP.md Phase 4).</p>
 */
@Mixin(LightningBolt.class)
public abstract class LightningBoltSoundMixin {

    @Unique
    private boolean playingwithstatic$playedStrike;

    /** Silence vanilla's first-tick thunder + impact (both calls match this @At). */
    @Redirect(
            method = "tick",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/Level;playLocalSound(DDDLnet/minecraft/sounds/SoundEvent;"
                            + "Lnet/minecraft/sounds/SoundSource;FFZ)V"),
            require = 0)
    private void playingwithstatic$muteEarlySound(Level level, double x, double y, double z, SoundEvent sound,
                                        SoundSource source, float volume, float pitch, boolean distance) {
        // plays on the strike tick instead, see below
    }

    /** Play the crack once, when the return stroke lands -- but not for a Sunwell-owned bolt; that's
     *  Sunwell's own retimed thunder to play, on its own timing, not ours to duplicate. */
    @Inject(method = "tick", at = @At("HEAD"), require = 0)
    private void playingwithstatic$strikeSound(CallbackInfo ci) {
        LightningBolt self = (LightningBolt) (Object) this;
        Level level = self.level();
        if (!level.isClientSide || playingwithstatic$playedStrike || self.tickCount < BoltRenderer.STRIKE_TICK) {
            return;
        }
        BlockPos strike = self.blockPosition();
        if (SunwellCompat.isSunwellBolt(level, strike)) {
            return;
        }
        playingwithstatic$playedStrike = true;
        double x = self.getX();
        double y = self.getY();
        double z = self.getZ();
        float pitch = 0.85F + level.random.nextFloat() * 0.2F;
        level.playLocalSound(x, y, z, SoundEvents.LIGHTNING_BOLT_IMPACT, SoundSource.WEATHER, 1.4F, pitch, false);
        level.playLocalSound(x, y, z, SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.WEATHER, 2.2F, pitch, false);
    }
}
