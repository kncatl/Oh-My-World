package com.kncatl.ohmyworld.platform.fabric;

//? if FABRIC {
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import com.kncatl.ohmyworld.platform.Platform;

public class FabricEvents {

    public static void register() {
        ServerWorldEvents.LOAD.register((server, world) -> {
            if (!(world instanceof ServerLevel sl)) return;
            if (sl.dimension() != Level.OVERWORLD) return;
            ((FabricPlatform) Platform.get()).dispatchLevelLoad(sl);
        });
    }
}
//?}