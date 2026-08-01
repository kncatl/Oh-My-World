package com.kncatl.ohmyworld.platform.neoforge;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.event.level.LevelEvent;

import com.kncatl.ohmyworld.FlatPattern;
import com.kncatl.ohmyworld.platform.Platform;

public class NeoForgeEvents {

    @EventBusSubscriber(modid = FlatPattern.MODID)
    public static class Common {
        @SubscribeEvent
        public static void onLevelLoad(LevelEvent.Load event) {
            if (!(event.getLevel() instanceof ServerLevel sl)) return;
            if (sl.dimension() != Level.OVERWORLD) return;
            ((NeoForgePlatform) Platform.get()).dispatchLevelLoad(sl);
        }
    }

    @EventBusSubscriber(modid = FlatPattern.MODID, value = Dist.CLIENT)
    public static class Client {
        @SubscribeEvent
        public static void onScreenRenderPost(ScreenEvent.Render.Post event) {
            ((NeoForgePlatform) Platform.get()).dispatchScreenRender((Screen) event.getScreen());
        }
    }
}