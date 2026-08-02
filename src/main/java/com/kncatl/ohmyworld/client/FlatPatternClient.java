package com.kncatl.ohmyworld.client;

//? if NEOFORGE {
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
//? >=1.21.11 {
import net.minecraft.resources.Identifier;
//?} else {
import net.minecraft.resources.ResourceLocation;
//?}
import net.minecraft.world.level.levelgen.presets.WorldPreset;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.RegisterPresetEditorsEvent;

@EventBusSubscriber(modid = "ohmyworld", value = Dist.CLIENT)
public class FlatPatternClient {

    @SubscribeEvent
    public static void registerPresetEditors(RegisterPresetEditorsEvent event) {
        event.register(FlatPatternScreenEvents.OUR_KEY, CustomFlatScreen::new);
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        FlatPatternScreenEvents.register();
    }
}
//?}