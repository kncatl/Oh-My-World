package com.kncatl.flatpattern;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.LevelEvent;

@EventBusSubscriber(modid = FlatPattern.MODID)
public class WorldLoadHandler {

    @SubscribeEvent
    public static void onLevelLoad(LevelEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel sl)) return;
        if (sl.dimension() != Level.OVERWORLD) return;

        if (!PatternData.restoreFromMarker(sl) && PatternData.isPending()) {
            PatternData.markActive(sl);
        }
    }
}
