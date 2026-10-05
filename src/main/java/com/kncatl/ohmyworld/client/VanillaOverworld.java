package com.kncatl.ohmyworld.client;

import net.minecraft.client.gui.screens.worldselection.WorldCreationContext;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;

import com.kncatl.ohmyworld.OhMyWorldConfig;
import com.kncatl.ohmyworld.PatternData;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

/**
 * 「公式未定义 overworld 节时，创建出的世界主世界按原版生成」。
 *
 * <p>在“创建新世界”真正开始的那一刻（{@code CreateWorldScreen.onCreate}）把世界
 * 创建上下文换成“主世界生成器 = 原版噪声生成器”的版本；只对新建世界生效——生成器在
 * 创建时固化进 level.dat，已有世界不受影响。
 *
 * <p>注入点说明：创建世界界面<b>不</b>走 {@code WorldOpenFlows.createFreshLevel}
 * （那是主菜单快速创建入口），而是 {@code onCreate → selectedDimensions().bake()
 * → createLevelFromExistingSettings}；因此由 {@code MixinCreateWorldScreen}
 * 注入 onCreate 里的 {@code getSettings()} 调用，避免污染编辑期间的世界类型显示。
 */
public final class VanillaOverworld {
    private static final Logger LOGGER = LogUtils.getLogger();

    /** 条件：flat_plus 创建流程中、公式显式设置过、且没有 overworld 节。 */
    public static boolean shouldReplace() {
        return PatternData.isPending()
                && PatternData.hasExplicitFormula()
                && !PatternData.hasFormulaFor(Level.OVERWORLD);
    }

    /** 供注入点调用：满足条件时返回“主世界换成原版”的世界创建上下文，否则原样返回。 */
    public static WorldCreationContext maybeReplace(WorldCreationContext context) {
        if (shouldReplace()) {
            if (OhMyWorldConfig.debugLogsEnabled()) {
                LOGGER.info("ohmyworld: [client] formula has no overworld section; "
                        + "the new world's overworld will use vanilla generation");
            }
            return context.withDimensions((access, dimensions) ->
                    dimensions.replaceOverworldGenerator(access, vanillaOverworldGenerator(access)));
        }
        if (PatternData.isPending() && OhMyWorldConfig.debugLogsEnabled()) {
            LOGGER.info("ohmyworld: [client] overworld replacement skipped "
                            + "(explicit formula = {}, overworld section = {})",
                    PatternData.hasExplicitFormula(), PatternData.hasFormulaFor(Level.OVERWORLD));
        }
        return context;
    }

    /** 原版主世界生成器（与“默认”世界类型的基座一致）。 */
    private static NoiseBasedChunkGenerator vanillaOverworldGenerator(HolderLookup.Provider access) {
        MultiNoiseBiomeSource biomeSource = MultiNoiseBiomeSource.createFromPreset(
                access.lookupOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST)
                        .getOrThrow(MultiNoiseBiomeSourceParameterLists.OVERWORLD));
        Holder<NoiseGeneratorSettings> settings = access.lookupOrThrow(Registries.NOISE_SETTINGS)
                .getOrThrow(NoiseGeneratorSettings.OVERWORLD);
        return new NoiseBasedChunkGenerator(biomeSource, settings);
    }

    private VanillaOverworld() {}
}
