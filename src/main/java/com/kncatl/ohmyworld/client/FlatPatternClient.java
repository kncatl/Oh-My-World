package com.kncatl.ohmyworld.client;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.presets.WorldPreset;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterPresetEditorsEvent;

@EventBusSubscriber(modid = "ohmyworld", value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public class FlatPatternClient {

    static final ResourceKey<WorldPreset> OUR_KEY =
            ResourceKey.create(Registries.WORLD_PRESET,
                    ResourceLocation.fromNamespaceAndPath("ohmyworld", "flat_plus"));

    @SubscribeEvent
    public static void registerPresetEditors(RegisterPresetEditorsEvent event) {
        event.register(OUR_KEY, CustomFlatScreen::new);
    }
}
