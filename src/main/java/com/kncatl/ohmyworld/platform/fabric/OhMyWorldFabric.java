package com.kncatl.ohmyworld.platform.fabric;

//? if FABRIC {
import net.fabricmc.api.ModInitializer;

import com.kncatl.ohmyworld.FlatPattern;
import com.kncatl.ohmyworld.OhMyWorldConfig;
import com.kncatl.ohmyworld.WorldLoadHandler;
import com.kncatl.ohmyworld.platform.Platform;

public class OhMyWorldFabric implements ModInitializer {

    @Override
    public void onInitialize() {
        Platform.set(new FabricPlatform());
        OhMyWorldConfig.load();
        FlatPattern.copyGuideFiles();
        WorldLoadHandler.register();
        FabricEvents.register();
    }
}
//?}