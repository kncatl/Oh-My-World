package com.kncatl.flatpattern;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

@EventBusSubscriber(modid = FlatPattern.MODID, bus = EventBusSubscriber.Bus.MOD)
public class ChunkGenRegistry {

    @SubscribeEvent
    public static void onRegister(RegisterEvent event) {
        event.register(Registries.CHUNK_GENERATOR,
                ResourceLocation.fromNamespaceAndPath(FlatPattern.MODID, "pattern_flat"),
                () -> PatternFlatSource.CODEC);
    }
}
