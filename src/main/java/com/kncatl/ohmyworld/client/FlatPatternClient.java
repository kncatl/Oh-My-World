package com.kncatl.ohmyworld.client;

//? if NEOFORGE {
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterPresetEditorsEvent;

import com.kncatl.ohmyworld.PresetKeys;

// No `bus` parameter: FancyModLoader routes by the subscribed event's type.
// Events implementing IModBusEvent (such as RegisterPresetEditorsEvent) go to
// the mod bus, everything else to the game bus. Explicitly passing
// EventBusSubscriber.Bus.MOD is deprecated for removal and is already ignored
// as of FML 4.0.x, which is why both supported NeoForge lines behave the same.
@EventBusSubscriber(modid = "ohmyworld", value = Dist.CLIENT)
public class FlatPatternClient {

    @SubscribeEvent
    public static void registerPresetEditors(RegisterPresetEditorsEvent event) {
        event.register(PresetKeys.OUR_KEY, CustomFlatScreen::new);
    }

}
//?}
