package com.kncatl.ohmyworld.client;

import java.util.Objects;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
//? >=1.21.11 {
import net.minecraft.resources.Identifier;
//?} else {
import net.minecraft.resources.ResourceLocation;
//?}

import com.kncatl.ohmyworld.PatternData;
import com.kncatl.ohmyworld.platform.Platform;

public final class FlatPatternScreenEvents {

    //? >=1.21.11 {
    private static Identifier lastPresetKey;
    //?} else {
    private static ResourceLocation lastPresetKey;
    //?}

    public static void register() {
        Platform.get().onScreenRenderPost(FlatPatternScreenEvents::onScreenRenderPost);
    }

    private static void onScreenRenderPost(Screen screen) {
        if (!(screen instanceof CreateWorldScreen cw)) return;

        WorldCreationUiState state = cw.getUiState();
        if (state.getWorldType() == null) return;
        var holder = state.getWorldType().preset();
        if (holder == null) return;
        //? >=1.21.11 {
        Identifier key = holder.unwrapKey().map(k -> k.identifier()).orElse(null);
        //?} else {
        ResourceLocation key = holder.unwrapKey().map(k -> k.location()).orElse(null);
        //?}
        if (key == null) return;

        if (Objects.equals(key, lastPresetKey)) return;
        lastPresetKey = key;

        //? >=1.21.11 {
        if (!FlatPatternClient.OUR_KEY.identifier().equals(key)) {
        //?} else {
        if (!FlatPatternClient.OUR_KEY.location().equals(key)) {
        //?}
            PatternData.clearActive();
            PatternData.clearPending();
            return;
        }

        PatternData.setPending();
    }
}