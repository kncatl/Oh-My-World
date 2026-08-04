package com.kncatl.ohmyworld.platform.neoforge;

//? if NEOFORGE {
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import com.kncatl.ohmyworld.FlatPattern;
import com.kncatl.ohmyworld.platform.Platform;

public class NeoForgeEvents {

    @EventBusSubscriber(modid = FlatPattern.MODID)
    public static class Common {
        @SubscribeEvent
        public static void onLevelLoad(LevelEvent.Load event) {
            if (!(event.getLevel() instanceof ServerLevel sl)) return;
            ((NeoForgePlatform) Platform.get()).dispatchLevelLoad(sl);
        }

        @SubscribeEvent
        public static void onServerTick(ServerTickEvent.Post event) {
            ((NeoForgePlatform) Platform.get()).dispatchServerTick(event.getServer());
        }
    }
}
//?}
