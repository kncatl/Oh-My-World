package com.kncatl.ohmyworld.platform.fabric;

//? if FABRIC {
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
//? >=26.1 {
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLevelEvents;
//?} else {
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents;
import net.minecraft.server.level.ServerLevel;
//?}

import com.kncatl.ohmyworld.platform.Platform;

public class FabricEvents {

    public static void register() {
        //? >=26.1 {
        // Fabric API 26.x：ServerWorldEvents 改名为 ServerLevelEvents，回调直接给 ServerLevel
        ServerLevelEvents.LOAD.register((server, level) ->
                ((FabricPlatform) Platform.get()).dispatchLevelLoad(level));
        //?} else {
        ServerWorldEvents.LOAD.register((server, world) -> {
            if (!(world instanceof ServerLevel sl)) return;
            ((FabricPlatform) Platform.get()).dispatchLevelLoad(sl);
        });
        //?}
        ServerTickEvents.END_SERVER_TICK.register(server ->
                ((FabricPlatform) Platform.get()).dispatchServerTick(server));
    }
}
//?}
