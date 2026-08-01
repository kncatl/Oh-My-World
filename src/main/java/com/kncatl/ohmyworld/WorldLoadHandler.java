package com.kncatl.ohmyworld;

import net.minecraft.server.level.ServerLevel;

import com.kncatl.ohmyworld.platform.Platform;

public final class WorldLoadHandler {

    public static void register() {
        Platform.get().onLevelLoad(WorldLoadHandler::onLevelLoad);
    }

    private static void onLevelLoad(ServerLevel sl) {
        OhMyWorldConfig config = OhMyWorldConfig.load();

        if (config.serverMode()) {
            PatternData.set(FormulaParser.parse(config.formula()), config.formula());
        } else if (!PatternData.restoreFromMarker(sl)) {
            if (PatternData.isPending()) {
                PatternData.markActive(sl);
            } else {
                PatternData.clearActive();
            }
        }
    }
}