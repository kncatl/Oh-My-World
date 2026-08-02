package com.kncatl.ohmyworld.platform.fabric;

//? if FABRIC {
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.server.level.ServerLevel;

import com.kncatl.ohmyworld.platform.LevelLoadCallback;
import com.kncatl.ohmyworld.platform.Platform;
import com.kncatl.ohmyworld.platform.ScreenRenderCallback;

public class FabricPlatform implements Platform {

    private final List<LevelLoadCallback> levelCallbacks = new ArrayList<>();
    private final List<ScreenRenderCallback> screenCallbacks = new ArrayList<>();

    public FabricPlatform() {
        Platform.set(this);
    }

    @Override
    public Path gameDir() {
        return FabricLoader.getInstance().getGameDir();
    }

    @Override
    public Path configDir() {
        return FabricLoader.getInstance().getConfigDir();
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

    void dispatchScreenRender(Screen screen) {
        for (ScreenRenderCallback cb : screenCallbacks) cb.onRender(screen);
    }
}
//?}