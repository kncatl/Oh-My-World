package com.kncatl.ohmyworld.client;

//? if NEOFORGE {
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterPresetEditorsEvent;

import com.kncatl.ohmyworld.PresetKeys;

//? >=1.21.11 {
@EventBusSubscriber(modid = "ohmyworld", value = Dist.CLIENT)
//?} else {
@EventBusSubscriber(modid = "ohmyworld", bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
//?}
public class FlatPatternClient {

    @SubscribeEvent
    public static void registerPresetEditors(RegisterPresetEditorsEvent event) {
        event.register(PresetKeys.OUR_KEY, CustomFlatScreen::new);
    }

}
//?}
