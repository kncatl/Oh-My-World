package com.kncatl.ohmyworld.client;

import java.util.Objects;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
//? >=1.21.11 {
import net.minecraft.resources.Identifier;
//?} else {
import net.minecraft.resources.ResourceLocation;
//?}
import net.minecraft.world.level.levelgen.presets.WorldPreset;

import com.kncatl.ohmyworld.PatternData;
import com.kncatl.ohmyworld.platform.Platform;

public final class FlatPatternScreenEvents {

    // ohmyworld:flat_plus 预设键（加载器通用）
    static final ResourceKey<WorldPreset> OUR_KEY =
            ResourceKey.create(Registries.WORLD_PRESET,
                    //? >=1.21.11 {
                    Identifier.fromNamespaceAndPath("ohmyworld", "flat_plus"));
                    //?} else {
                    ResourceLocation.fromNamespaceAndPath("ohmyworld", "flat_plus"));
                    //?}

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
        if (!OUR_KEY.identifier().equals(key)) {
        //?} else {
        if (!OUR_KEY.location().equals(key)) {
        //?}
            PatternData.clearActive();
            PatternData.clearPending();
            return;
        }

        PatternData.setPending();
    }
}