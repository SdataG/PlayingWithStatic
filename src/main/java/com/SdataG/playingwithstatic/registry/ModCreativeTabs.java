package com.SdataG.playingwithstatic.registry;

import com.SdataG.playingwithstatic.PlayingWithStatic;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;

/** Puts the capacitor in the vanilla Redstone Blocks tab for now, next to the vanilla lightning rod. */
@EventBusSubscriber(modid = PlayingWithStatic.MOD_ID)
public final class ModCreativeTabs {

    private ModCreativeTabs() {
    }

    @SubscribeEvent
    public static void onBuildContents(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() != CreativeModeTabs.REDSTONE_BLOCKS) {
            return;
        }
        event.insertAfter(
                new ItemStack(Items.LIGHTNING_ROD),
                new ItemStack(ModBlocks.CAPACITOR_ITEM.get()),
                CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS);
    }
}
