package com.SdataG.playingwithstatic.mixin.client;

import com.SdataG.playingwithstatic.client.render.BoltRenderer;
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
 * Moves every bolt's thunder/impact sound to the moment it actually strikes. Vanilla fires both sounds
 * on the bolt's first tick; our animation spends its first several ticks on the dim leader creeping
 * down, and only lands the bright return stroke at {@link BoltRenderer#STRIKE_TICK}. This mutes
 * vanilla's early sound and plays it on the strike tick instead -- the flash and the crack together.
 *
 * <p><b>Both {@code require = 0}, deliberately.</b> Sunwell (this mod's own sibling project) redirects
 * the exact same {@code Level.playLocalSound} call on the exact same {@code LightningBolt.tick} for the
 * identical reason. Sponge Mixin's {@code @Redirect} claims a specific bytecode instruction -- only one
 * mod's redirect can win it, and whichever loses finds zero remaining call sites. With the default
 * {@code require = 1} that's a hard crash at boot (confirmed: this exact crash, with both mods installed
 * in the same instance). {@code require = 0} makes a lost race a silent no-op instead: if Sunwell wins,
 * our mute doesn't apply and vanilla's early thunder plays alongside our own strike-tick thunder below
 * (a doubled-thunder audio bug when both mods are present), which is a real but minor cosmetic
 * regression -- nowhere near as bad as the game refusing to start. See ROADMAP.md Phase 4 for the actual
 * fix (coordinating which mod owns the redirect, or restructuring so they don't collide at all).</p>
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

    /** Play the crack once, when the return stroke lands. */
    @Inject(method = "tick", at = @At("HEAD"), require = 0)
    private void playingwithstatic$strikeSound(CallbackInfo ci) {
        LightningBolt self = (LightningBolt) (Object) this;
        Level level = self.level();
        if (!level.isClientSide || playingwithstatic$playedStrike || self.tickCount < BoltRenderer.STRIKE_TICK) {
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
