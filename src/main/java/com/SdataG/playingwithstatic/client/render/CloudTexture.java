package com.SdataG.playingwithstatic.client.render;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.util.FastColor;
import net.minecraft.util.Mth;

import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;

/**
 * Reads vanilla's own cloud texture ({@code textures/environment/clouds.png}) to tell whether a world
 * position falls under an actual rendered cloud "puff" or a gap between them -- used to align the sky
 * origin with a real cloud instead of an arbitrary point (see {@code BoltRenderer}).
 *
 * <p>Replicates the exact mapping vanilla's own {@code LevelRenderer.renderClouds}/{@code buildClouds}
 * use, confirmed against the real 1.21.1 sources rather than assumed: 1 texel = 12 world blocks, the
 * texture scrolls horizontally over time at 0.03 blocks/tick with a fixed +0.33 vertical (Z) offset, and
 * the texture itself (confirmed by inspecting the actual shipped 256x256 PNG) is a hard-edged binary
 * alpha mask -- every pixel is either fully transparent (a gap) or fully opaque white (a cloud), nothing
 * in between.</p>
 */
public final class CloudTexture {

    private static final ResourceLocation LOCATION = ResourceLocation.withDefaultNamespace("textures/environment/clouds.png");
    private static final float BLOCKS_PER_TEXEL = 12.0F;
    private static final float SCROLL_PER_TICK = 0.03F;
    private static final float Z_OFFSET = 0.33F;

    private static NativeImage image;
    private static boolean loadAttempted;

    private CloudTexture() {
    }

    private static NativeImage image() {
        if (!loadAttempted) {
            loadAttempted = true;
            try {
                Optional<Resource> resource = Minecraft.getInstance().getResourceManager().getResource(LOCATION);
                if (resource.isPresent()) {
                    try (InputStream stream = resource.get().open()) {
                        image = NativeImage.read(stream);
                    }
                }
            } catch (IOException | RuntimeException error) {
                image = null; // fail-open: every position reads as "not a cloud", callers fall back
            }
        }
        return image;
    }

    /**
     * True if the texel at {@code (worldX, worldZ)} at the given game time, AND all 8 of its
     * neighbors, are cloud -- solidly inside a puff, not right at its edge. The 8-neighbor check matters
     * because the texture is a hard-edged mask (see class docs), not a soft gradient: a single-pixel
     * check would happily pick a point one texel from a gap.
     */
    public static boolean isCloudCenter(double worldX, double worldZ, float gameTime) {
        NativeImage img = image();
        if (img == null) {
            return false;
        }
        float scrollX = gameTime * SCROLL_PER_TICK;
        float texelU = (float) ((worldX + scrollX) / BLOCKS_PER_TEXEL);
        float texelV = (float) (worldZ / BLOCKS_PER_TEXEL + Z_OFFSET);
        int centerX = Mth.floor(texelU);
        int centerY = Mth.floor(texelV);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                int px = Math.floorMod(centerX + dx, img.getWidth());
                int py = Math.floorMod(centerY + dy, img.getHeight());
                if (FastColor.ABGR32.alpha(img.getPixelRGBA(px, py)) == 0) {
                    return false;
                }
            }
        }
        return true;
    }
}
