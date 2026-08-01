package com.kncatl.ohmyworld.client;

import java.util.Objects;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.resources.ResourceLocation;

import com.kncatl.ohmyworld.PatternData;
import com.kncatl.ohmyworld.platform.Platform;

public final class FlatPatternScreenEvents {

    private static ResourceLocation lastPresetKey;

    public static void register() {
        Platform.get().onScreenRenderPost(FlatPatternScreenEvents::onScreenRenderPost);
    }

    private static void onScreenRenderPost(Screen screen) {
        if (!(screen instanceof CreateWorldScreen cw)) return;

        WorldCreationUiState state = cw.getUiState();
        if (state.getWorldType() == null) return;
        var holder = state.getWorldType().preset();
        if (holder == null) return;
        ResourceLocation key = holder.unwrapKey().map(k -> k.location()).orElse(null);
        if (key == null) return;

        if (Objects.equals(key, lastPresetKey)) return;
        lastPresetKey = key;

        if (!FlatPatternClient.OUR_KEY.location().equals(key)) {
            PatternData.clearActive();
            PatternData.clearPending();
            return;
        }

        PatternData.setPending();
    }
}