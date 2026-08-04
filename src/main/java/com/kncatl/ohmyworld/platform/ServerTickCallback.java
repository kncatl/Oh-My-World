package com.kncatl.ohmyworld.platform;

import net.minecraft.server.MinecraftServer;

@FunctionalInterface
public interface ServerTickCallback {
    void onTick(MinecraftServer server);
}
