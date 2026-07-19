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

    /**
     * Model/shape only for now, same as the capacitor above — no generation logic yet. Meant to wrap
     * around a placed copper lightning rod: a low-tier, storm-independent trickle generator (Phase 3)
     * that collects ambient static from mobs walking near it, carpet, and other nearby items, and
     * feeds it into whatever the rod is connected to. Does not store charge itself.
     */
    public static final DeferredHolder<Block, Block> STATIC_COLLECTOR = BLOCKS.register("static_collector",
            () -> new Block(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_ORANGE)
                    .strength(3.0F, 3.0F)
                    .sound(SoundType.COPPER)
                    .requiresCorrectToolForDrops()
                    .noOcclusion()));

    public static final DeferredHolder<Item, Item> STATIC_COLLECTOR_ITEM = ITEMS.register("static_collector",
            () -> new BlockItem(STATIC_COLLECTOR.get(), new Item.Properties()));

    private ModBlocks() {
    }
}
