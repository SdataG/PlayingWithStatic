package com.SdataG.playingwithstatic;

import com.SdataG.playingwithstatic.registry.ModBlocks;
import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;

@Mod(PlayingWithStatic.MOD_ID)
public class PlayingWithStatic {

    public static final String MOD_ID = "playingwithstatic";
    public static final Logger LOGGER = LogUtils.getLogger();

    public PlayingWithStatic(IEventBus modBus, ModContainer container) {
        ModBlocks.BLOCKS.register(modBus);
        ModBlocks.ITEMS.register(modBus);
    }
}
