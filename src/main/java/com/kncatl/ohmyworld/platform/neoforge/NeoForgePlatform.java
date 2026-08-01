package com.kncatl.ohmyworld.platform.neoforge;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.server.level.ServerLevel;
import net.neoforged.fml.loading.FMLPaths;

import com.kncatl.ohmyworld.platform.LevelLoadCallback;
import com.kncatl.ohmyworld.platform.Platform;
import com.kncatl.ohmyworld.platform.ScreenRenderCallback;

public class NeoForgePlatform implements Platform {

    private final List<LevelLoadCallback> levelCallbacks = new ArrayList<>();
    private final List<ScreenRenderCallback> screenCallbacks = new ArrayList<>();

    public NeoForgePlatform() {
        Platform.set(this);
    }

    @Override
    public Path gameDir() {
        return FMLPaths.GAMEDIR.get();
    }

    @Override
    public Path configDir() {
        return FMLPaths.CONFIGDIR.get();
    }

    @Override
    public void onLevelLoad(LevelLoadCallback callback) {
        levelCallbacks.add(callback);
    }

    @Override
    public void onScreenRenderPost(ScreenRenderCallback callback) {
        screenCallbacks.add(callback);
    }

    void dispatchLevelLoad(ServerLevel level) {
        for (LevelLoadCallback cb : levelCallbacks) cb.onLoad(level);
    }

    void dispatchScreenRender(net.minecraft.client.gui.screens.Screen screen) {
        for (ScreenRenderCallback cb : screenCallbacks) cb.onRender(screen);
    }
}