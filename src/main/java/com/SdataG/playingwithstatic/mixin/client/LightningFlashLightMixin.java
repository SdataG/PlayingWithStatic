package com.SdataG.playingwithstatic.mixin.client;

import com.SdataG.playingwithstatic.client.render.BoltFlashLight;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.lighting.LightEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Lets an active bolt's strike-point flash ({@link BoltFlashLight}) actually light the room, by
 * boosting the client's BLOCK light engine read at nearby positions. Client-only (hence living under
 * the "client" mixin list despite {@link LightEngine} being a common class) since
 * {@link Minecraft#getInstance()} is a client-only API and {@code BoltFlashLight} is only ever
 * populated by {@code BoltRenderer}, which only runs on the render thread.
 */
@Mixin(LightEngine.class)
public abstract class LightningFlashLightMixin {

    @Unique
    private static Level playingwithstatic$cachedLevel;
    @Unique
    private static Object playingwithstatic$cachedBlockEngine;

    @Inject(method = "getLightValue(Lnet/minecraft/core/BlockPos;)I", at = @At("RETURN"), cancellable = true)
    private void playingwithstatic$boostFlash(BlockPos pos, CallbackInfoReturnable<Integer> cir) {
        Level level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        if (level != playingwithstatic$cachedLevel) {
            playingwithstatic$cachedLevel = level;
            playingwithstatic$cachedBlockEngine = level.getChunkSource().getLightEngine().getLayerListener(LightLayer.BLOCK);
        }
        if ((Object) this != playingwithstatic$cachedBlockEngine) {
            return;
        }
        int flash = BoltFlashLight.extraLightAt(pos.getX(), pos.getY(), pos.getZ());
        if (flash > 0 && flash > cir.getReturnValueI()) {
            cir.setReturnValue(Math.min(15, flash));
        }
    }
}
