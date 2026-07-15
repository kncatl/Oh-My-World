package com.kncatl.ohmyworld;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

@EventBusSubscriber(modid = "ohmyworld", bus = EventBusSubscriber.Bus.MOD)
public class ChunkGenRegistry {
    @SubscribeEvent
    public static void onRegister(RegisterEvent event) {
        event.register(Registries.CHUNK_GENERATOR,
                ResourceLocation.fromNamespaceAndPath("ohmyworld", "pattern_flat"),
                () -> PatternFlatSource.CODEC);
    }
}
