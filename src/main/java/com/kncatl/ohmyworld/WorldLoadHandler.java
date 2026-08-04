package com.kncatl.ohmyworld;

import java.util.List;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.FlatLevelSource;

import com.kncatl.ohmyworld.platform.Platform;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

public final class WorldLoadHandler {
    private static final Logger LOGGER = LogUtils.getLogger();

    private static int tickCount;

    public static void register() {
        Platform.get().onLevelLoad(WorldLoadHandler::onLevelLoad);
        Platform.get().onServerTick(WorldLoadHandler::onServerTick);
    }

    private static void onLevelLoad(ServerLevel sl) {
        // 每次世界加载时重新读取配置文件
        OhMyWorldConfig config = OhMyWorldConfig.reload();
        FlatLevelSource generator = sl.getChunkSource().getGenerator() instanceof FlatLevelSource flat ? flat : null;

        if (config.serverMode()) {
            if (!applyConfigFormula(config)) {
                PatternData.clearActive();
                PatternData.clearAllGenerators();
                return;
            }
            if (generator != null) PatternData.bindGenerator(generator);
            // server_mode 下强制覆写主世界 marker，保证之后关闭 server_mode 时世界恢复的是配置公式
            if (sl.dimension() == Level.OVERWORLD) PatternData.markActive(sl, true);
            return;
        }

        if (generator == null) return;

        // 客户端刚通过创建界面以 flat_plus 预设新建的世界：写入/恢复 marker。
        // （created 仅在 createFreshLevel 且 flat_plus 选中时置位；
        //   加载已有世界时 openWorld 会清除该信号。）
        if (PatternData.isCreated()) {
            PatternData.clearCreated();
            if (!PatternData.restoreFromMarker(sl)) {
                PatternData.markActive(sl);
            }
            PatternData.bindGenerator(generator);
            return;
        }

        if (!PatternData.restoreFromMarker(sl)) {
            if (PatternData.isPending()) {
                PatternData.markActive(sl);
                PatternData.bindGenerator(generator);
            } else {
                PatternData.clearGenerator(generator);
                if (sl.getChunkSource().getGenerator() instanceof FlatLevelSource) {
                    // 超平坦世界但没有公式 marker：无 UI 的专用服务器无法写入 marker，
                    // 需要在 config/ohmyworld.json 开启 server_mode
                    LOGGER.info("ohmyworld: world '{}' uses a flat generator but has no formula marker; "
                            + "on dedicated servers enable server_mode in config/ohmyworld.json to apply a formula",
                            sl.getServer().getWorldData().getLevelName());
                }
            }
        } else {
            PatternData.bindGenerator(generator);
        }
        PatternData.clearPending();
    }

    /** 运行中热加载：server_mode 下每 5 秒检查一次配置文件，变化时立即应用新公式。 */
    private static void onServerTick(MinecraftServer server) {
        if (++tickCount % 100 != 0) return;

        if (!OhMyWorldConfig.reloadIfChanged()) return;

        OhMyWorldConfig config = OhMyWorldConfig.load();
        if (config.serverMode()) {
            // 热加载失败时保留上一次有效快照，不让临时半写入文件破坏正在生成的世界。
            if (applyConfigFormula(config)) {
                PatternData.bindAllGenerators();
                ServerLevel overworld = server.overworld();
                if (overworld != null) PatternData.markActive(overworld, true);
            }
        } else {
            // server_mode 刚被关闭：恢复该世界 marker 中记录的公式（若有）
            ServerLevel overworld = server.overworld();
            if (overworld == null
                    || !(overworld.getChunkSource().getGenerator() instanceof FlatLevelSource)
                    || !PatternData.restoreFromMarker(overworld)) {
                PatternData.clearAllGenerators();
                PatternData.clearActive();
            } else {
                PatternData.bindAllGenerators();
            }
        }
    }

    private static boolean applyConfigFormula(OhMyWorldConfig config) {
        FormulaParser.ParseResult result = FormulaParser.parseWithErrors(config.formula());
        if (!PatternData.setIfValid(result, config.formula())) {
            logErrors(result.errors());
            if (result.errors().isEmpty()) LOGGER.error("ohmyworld: config formula contains no valid layers");
            return false;
        }
        return true;
    }

    private static void logErrors(List<String> errors) {
        for (String e : errors) LOGGER.error("ohmyworld: formula error: {}", e);
    }
}
