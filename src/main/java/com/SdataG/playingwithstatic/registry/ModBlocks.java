package com.SdataG.playingwithstatic.registry;

import com.SdataG.playingwithstatic.PlayingWithStatic;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModBlocks {

    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(BuiltInRegistries.BLOCK, PlayingWithStatic.MOD_ID);
    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(BuiltInRegistries.ITEM, PlayingWithStatic.MOD_ID);

    // Phase 1: capacitor block goes here.

    private ModBlocks() {
    }
}
