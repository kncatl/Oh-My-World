package com.kncatl.ohmyworld.platform.neoforge;

//? if NEOFORGE {
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.fml.loading.FMLPaths;

import com.kncatl.ohmyworld.platform.LevelLoadCallback;
import com.kncatl.ohmyworld.platform.Platform;
import com.kncatl.ohmyworld.platform.ServerTickCallback;

public class NeoForgePlatform implements Platform {

    private final List<LevelLoadCallback> levelCallbacks = new ArrayList<>();
    private final List<ServerTickCallback> tickCallbacks = new ArrayList<>();

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
    public void onServerTick(ServerTickCallback callback) {
        tickCallbacks.add(callback);
    }

    void dispatchLevelLoad(ServerLevel level) {
        for (LevelLoadCallback cb : levelCallbacks) cb.onLoad(level);
    }

    void dispatchServerTick(MinecraftServer server) {
        for (ServerTickCallback cb : tickCallbacks) cb.onTick(server);
    }
}
//?}
