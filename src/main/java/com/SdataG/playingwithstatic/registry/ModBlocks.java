package com.SdataG.playingwithstatic.registry;

import com.SdataG.playingwithstatic.PlayingWithStatic;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModBlocks {

    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(BuiltInRegistries.BLOCK, PlayingWithStatic.MOD_ID);
    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(BuiltInRegistries.ITEM, PlayingWithStatic.MOD_ID);

    /**
     * Model/shape only for now (per ROADMAP.md Phase 1) — no FE capability, no rod hookup, no
     * arcing/condensing visual or overload behavior yet. Just the block/item so it can be placed and
     * looked at while the lightning VFX (build steps 2-4) is worked on in parallel.
     */
    public static final DeferredHolder<Block, Block> CAPACITOR = BLOCKS.register("capacitor",
            () -> new Block(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.METAL)
                    .strength(5.0F, 6.0F)
                    .sound(SoundType.METAL)
                    .requiresCorrectToolForDrops()
                    .noOcclusion()));

    public static final DeferredHolder<Item, Item> CAPACITOR_ITEM = ITEMS.register("capacitor",
            () -> new BlockItem(CAPACITOR.get(), new Item.Properties()));

    private ModBlocks() {
    }
}
