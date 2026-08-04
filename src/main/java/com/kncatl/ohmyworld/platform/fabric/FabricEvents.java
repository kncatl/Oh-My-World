package com.kncatl.ohmyworld.platform.fabric;

//? if FABRIC {
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents;
import net.minecraft.server.level.ServerLevel;

import com.kncatl.ohmyworld.platform.Platform;

public class FabricEvents {

    public static void register() {
        ServerWorldEvents.LOAD.register((server, world) -> {
            if (!(world instanceof ServerLevel sl)) return;
            ((FabricPlatform) Platform.get()).dispatchLevelLoad(sl);
        });
        ServerTickEvents.END_SERVER_TICK.register(server ->
                ((FabricPlatform) Platform.get()).dispatchServerTick(server));
    }
}
//?}
