package com.kncatl.ohmyworld.platform.neoforge;

//? if NEOFORGE {
import net.minecraft.client.gui.screens.Screen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;

import com.kncatl.ohmyworld.FlatPattern;
import com.kncatl.ohmyworld.client.FlatPatternScreenEvents;

@EventBusSubscriber(modid = FlatPattern.MODID, value = Dist.CLIENT)
public class NeoForgeClientEvents {
    @SubscribeEvent
    public static void onScreenRenderPost(ScreenEvent.Render.Post event) {
        FlatPatternScreenEvents.onScreenRenderPost((Screen) event.getScreen());
    }
}
//?}
