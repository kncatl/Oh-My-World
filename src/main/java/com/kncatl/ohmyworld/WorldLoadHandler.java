package com.kncatl.ohmyworld;

import java.util.List;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.FlatLevelSource;

import com.kncatl.ohmyworld.compat.ResourceIds;
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
        // 世界种子：公式内置变量 seed 的来源。加载事件早于任何区块生成
        // （各维度的公式绑定同样依赖这个时机）；各维度同值，先到先设。
        PatternData.setWorldSeed(sl.getSeed());

        // 每次世界加载时重新读取配置文件
        OhMyWorldConfig config = OhMyWorldConfig.reload();
        ChunkGenerator generator = sl.getChunkSource().getGenerator();
        ResourceKey<Level> dimension = sl.dimension();

        if (config.serverMode()) {
            if (!applyConfigFormula(config)) {
                PatternData.clearActive();
                PatternData.clearAllGenerators();
                return;
            }
            // server_mode：每个维度各按配置公式的对应节绑定；未写维度 = 不接管 = 原版。
            // 主世界额外覆写 marker，保证之后关闭 server_mode 时世界恢复的是配置公式。
            PatternData.bindGenerator(dimension, generator);
            if (dimension == Level.OVERWORLD) PatternData.markActive(sl, true);
            logDecision(sl, "server_mode", generator);
            return;
        }

        // 正常模式：公式来自世界根目录的 marker（创建世界时写入，整串含分节）。
        // 状态机（created/pending/marker）与具体维度无关，只有"本模组的世界"才接管；
        // 每个维度再按"该维度是否写了公式"决定是否绑定（未写 = 原版生成）。
        if (PatternData.isCreated()) {
            // 客户端刚通过创建界面以 flat_plus 预设新建的世界（createFreshLevel 置位）。
            // 公式没有 overworld 节时，主世界生成器已在创建时换成原版噪声生成器：
            // 它没有公式、直接走原版；marker 由先加载的维度写入，其余维度随后恢复。
            PatternData.clearCreated();
            if (!PatternData.restoreFromMarker(sl)) {
                PatternData.markActive(sl);
            }
            PatternData.clearPending();
            PatternData.bindGenerator(dimension, generator);
            logDecision(sl, "created", generator);
            return;
        }

        if (PatternData.restoreFromMarker(sl)) {
            // 已有世界 + marker = 本模组世界：按该维度是否写了公式决定是否接管
            PatternData.bindGenerator(dimension, generator);
            logDecision(sl, "marker", generator);
            return;
        }

        if (PatternData.isPending()) {
            // 创建流程的兜底信号（created 缺失时）：视为新建的本模组世界
            PatternData.markActive(sl);
            PatternData.clearPending();
            PatternData.bindGenerator(dimension, generator);
            logDecision(sl, "pending", generator);
            return;
        }

        // 不是本模组的世界（无 marker、无 pending）：不接管任何维度
        PatternData.clearGenerator(generator);
        LOGGER.debug("ohmyworld: level {} ignored (no marker); generator={}",
                ResourceIds.keyId(dimension), generator.getClass().getSimpleName());
        if (dimension == Level.OVERWORLD && generator instanceof FlatLevelSource) {
            // 超平坦世界但没有公式 marker：无 UI 的专用服务器无法写入 marker，
            // 需要在 config/ohmyworld.json 开启 server_mode
            LOGGER.info("ohmyworld: world '{}' uses a flat generator but has no formula marker; "
                    + "on dedicated servers enable server_mode in config/ohmyworld.json to apply a formula",
                    sl.getServer().getWorldData().getLevelName());
        }
    }

    /** 调试/支持用：记录某个维度加载时“是否被公式接管”的决定。 */
    private static void logDecision(ServerLevel sl, String source, ChunkGenerator generator) {
        LOGGER.info("ohmyworld: level {} ({}) generator={}, formula for this dimension={}, bound={}",
                ResourceIds.keyId(sl.dimension()), source, generator.getClass().getSimpleName(),
                PatternData.hasFormulaFor(sl.dimension()), PatternData.snapshotFor(generator) != null);
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
            if (overworld == null || !PatternData.restoreFromMarker(overworld)) {
                PatternData.clearAllGenerators();
                PatternData.clearActive();
            } else {
                PatternData.bindAllGenerators();
            }
        }
    }

    private static boolean applyConfigFormula(OhMyWorldConfig config) {
        FormulaParser.DimensionParseResult result = FormulaParser.parseDimensionsWithErrors(config.formula());
        if (!PatternData.setDimensions(result, config.formula())) {
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
