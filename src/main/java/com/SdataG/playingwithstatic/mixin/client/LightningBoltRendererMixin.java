package com.SdataG.playingwithstatic.mixin.client;

import com.SdataG.playingwithstatic.client.render.BoltRenderer;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.LightningBoltRenderer;
import net.minecraft.world.entity.LightningBolt;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Swaps vanilla's lightning render for ours, for every natural strike. The entity stays vanilla's, so
 * the strike keeps every real behaviour -- fire, damage, charged creepers, copper oxidising, and
 * vanilla's own rod-redirect and per-chunk frequency roll (locked decision 2). Only the look changes.
 */
@Mixin(LightningBoltRenderer.class)
public final class LightningBoltRendererMixin {

    private LightningBoltRendererMixin() {
    }

    @Inject(
            method = "render(Lnet/minecraft/world/entity/LightningBolt;FFLcom/mojang/blaze3d/vertex/PoseStack;"
                    + "Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At("HEAD"),
            cancellable = true,
            require = 0
    )
    private void playingwithstatic$replaceBolt(
            LightningBolt bolt,
            float entityYaw,
            float partialTick,
            PoseStack poseStack,
            MultiBufferSource buffers,
            int light,
            CallbackInfo callback
    ) {
        if (BoltRenderer.tryRender(bolt, partialTick, poseStack, buffers)) {
            callback.cancel();
        }
    }
}
