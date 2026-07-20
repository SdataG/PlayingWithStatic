package com.SdataG.playingwithstatic.registry;

import com.SdataG.playingwithstatic.PlayingWithStatic;
import com.SdataG.playingwithstatic.block.ShapedBlock;
import com.SdataG.playingwithstatic.block.StaticCollectorBlock;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModBlocks {

    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(BuiltInRegistries.BLOCK, PlayingWithStatic.MOD_ID);
    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(BuiltInRegistries.ITEM, PlayingWithStatic.MOD_ID);

    /** Bounding box of the capacitor model's actual geometry (src/main/resources/.../capacitor.json),
     *  in 1/16ths -- fitted so the selection outline/hitbox roughly matches what you see instead of a
     *  full 16x16x16 cube. A single box, not a per-element shape: the model isn't a simple box either,
     *  but this is a close enough envelope for a hitbox and far cheaper than an exact composite shape. */
    private static final VoxelShape CAPACITOR_SHAPE = Shapes.box(0.0, 0.0, 1.0 / 16, 15.0 / 16, 1.0, 15.0 / 16);

    /**
     * Model/shape only for now (per ROADMAP.md Phase 1) — no FE capability, no rod hookup, no
     * arcing/condensing visual or overload behavior yet. Just the block/item so it can be placed and
     * looked at while the lightning VFX (build steps 2-4) is worked on in parallel.
     */
    public static final DeferredHolder<Block, Block> CAPACITOR = BLOCKS.register("capacitor",
            () -> new ShapedBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.METAL)
                    .strength(5.0F, 6.0F)
                    .sound(SoundType.METAL)
                    .requiresCorrectToolForDrops()
                    .noOcclusion(),
                    CAPACITOR_SHAPE));

    public static final DeferredHolder<Item, Item> CAPACITOR_ITEM = ITEMS.register("capacitor",
            () -> new BlockItem(CAPACITOR.get(), new Item.Properties()));

    /** Bounding box of the static collector model's actual geometry -- a narrow central column (it
     *  wraps around a rod, it isn't a wide block), fitted the same way as the capacitor above. */
    private static final VoxelShape STATIC_COLLECTOR_SHAPE =
            Shapes.box(5.6 / 16, 0.0, 5.6 / 16, 10.4 / 16, 1.0, 10.4 / 16);

    /**
     * Model/shape only for now, same as the capacitor above — no generation logic yet. Meant to wrap
     * around a placed copper lightning rod: a low-tier, storm-independent trickle generator (Phase 3)
     * that collects ambient static from mobs walking near it, carpet, and other nearby items, and
     * feeds it into whatever the rod is connected to. Does not store charge itself. Only survives
     * directly under a copper lightning rod (see {@link StaticCollectorBlock}).
     */
    public static final DeferredHolder<Block, Block> STATIC_COLLECTOR = BLOCKS.register("static_collector",
            () -> new StaticCollectorBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_ORANGE)
                    .strength(3.0F, 3.0F)
                    .sound(SoundType.COPPER)
                    .requiresCorrectToolForDrops()
                    .noOcclusion(),
                    STATIC_COLLECTOR_SHAPE));

    public static final DeferredHolder<Item, Item> STATIC_COLLECTOR_ITEM = ITEMS.register("static_collector",
            () -> new BlockItem(STATIC_COLLECTOR.get(), new Item.Properties()));

    private ModBlocks() {
    }
}
