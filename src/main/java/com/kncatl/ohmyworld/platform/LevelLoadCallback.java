package com.kncatl.ohmyworld.platform;

import net.minecraft.server.level.ServerLevel;

@FunctionalInterface
public interface LevelLoadCallback {
    void onLoad(ServerLevel level);
}