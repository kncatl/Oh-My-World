package com.kncatl.ohmyworld;

import net.minecraft.server.level.ServerLevel;

import com.kncatl.ohmyworld.platform.Platform;

public final class WorldLoadHandler {

    public static void register() {
        Platform.get().onLevelLoad(WorldLoadHandler::onLevelLoad);
    }

    private static void onLevelLoad(ServerLevel sl) {
        // 每次世界加载时重新读取配置文件，支持运行中热修改 ohmyworld.json
        OhMyWorldConfig config = OhMyWorldConfig.reload();

        if (config.serverMode()) {
            PatternData.set(FormulaParser.parse(config.formula()), config.formula());
            // server_mode 下也写入 marker，保证之后关闭 server_mode 时世界仍能恢复自身公式
            PatternData.markActive(sl);
        } else if (!PatternData.restoreFromMarker(sl)) {
            if (PatternData.isPending()) {
                PatternData.markActive(sl);
            } else {
                PatternData.clearActive();
            }
        }
    }
}