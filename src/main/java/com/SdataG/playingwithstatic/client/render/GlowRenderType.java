package com.SdataG.playingwithstatic.client.render;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderType;

/**
 * Additive glow render type that depth-tests but does not depth-write, so it doesn't z-fight against
 * nearby geometry. Named {@code "lightning"} (not a custom name) so Iris/Sodium route it through the
 * same shader program vanilla lightning already uses there, rather than an unrecognized fallback that
 * can drop low-alpha fades entirely under a shaderpack.
 */
public final class GlowRenderType extends RenderType {

    private GlowRenderType(String name, VertexFormat format, VertexFormat.Mode mode, int bufferSize,
                           boolean affectsCrumbling, boolean sortOnUpload, Runnable setup, Runnable clear) {
        super(name, format, mode, bufferSize, affectsCrumbling, sortOnUpload, setup, clear);
    }

    public static final RenderType BOLT_GLOW = create(
            "lightning",
            DefaultVertexFormat.POSITION_COLOR,
            VertexFormat.Mode.QUADS,
            256,
            false,
            true,
            RenderType.CompositeState.builder()
                    .setShaderState(RENDERTYPE_LIGHTNING_SHADER)
                    .setTransparencyState(LIGHTNING_TRANSPARENCY)
                    .setWriteMaskState(COLOR_WRITE)
                    .setCullState(NO_CULL)
                    .createCompositeState(false));
}
