package com.kncatl.flatpattern.client;

import java.util.Objects;

import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorSettings;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;

import com.kncatl.flatpattern.PatternData;
import com.kncatl.flatpattern.PatternFlatSource;

@EventBusSubscriber(modid = "ohmyworld", value = Dist.CLIENT)
public class FlatPatternScreenEvents {

    private static ResourceLocation lastPresetKey;

    @SubscribeEvent
    public static void onScreenRender(ScreenEvent.Render.Post event) {
        if (!(event.getScreen() instanceof CreateWorldScreen screen)) return;

        WorldCreationUiState state = screen.getUiState();
        if (state.getWorldType() == null) return;
        var holder = state.getWorldType().preset();
        if (holder == null) return;
        ResourceLocation key = holder.unwrapKey().map(k -> k.location()).orElse(null);
        if (key == null) return;

        if (Objects.equals(key, lastPresetKey)) return;
        lastPresetKey = key;

        if (!FlatPatternClient.OUR_KEY.location().equals(key)) return;

        ChunkGenerator gen = state.getSettings().selectedDimensions().overworld();
        if (!(gen instanceof FlatLevelSource flatSource)) return;
        if (gen instanceof PatternFlatSource) return;

        FlatLevelGeneratorSettings settings = flatSource.settings();
        state.updateDimensions((reg, dims) ->
                dims.replaceOverworldGenerator(reg, new PatternFlatSource(settings)));
        PatternData.setPending();
    }
}
