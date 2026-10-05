package com.kncatl.ohmyworld.client;

import java.util.function.Function;

import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.WorldDimensions;

import com.kncatl.ohmyworld.PatternData;

/**
 * 「公式未定义 overworld 节时，创建出的世界主世界按原版生成」（方案 A）：
 * 在创建世界的最终一步，把主世界生成器从超平坦基座换成原版噪声生成器。
 *
 * <p>只对<b>新建世界</b>生效——生成器在创建时固化进 level.dat，已有世界不受影响。
 * 未设置过公式时不替换（默认公式含 overworld）；替换后主世界没有公式，
 * {@code WorldLoadHandler} 自然不接管它，也就是"缺失维度 = 原版"在主世界上同样成立。
 */
public final class VanillaOverworld {

    /** 是否满足替换条件：flat_plus 创建流程中、公式显式设置过、且没有 overworld 节。 */
    public static boolean shouldReplace() {
        return PatternData.isPending()
                && PatternData.hasExplicitFormula()
                && !PatternData.hasFormulaFor(Level.OVERWORLD);
    }

    //? >=1.21.2 {
    /** 1.21.2+ 的维度函数参数 / 替换 API 使用 HolderLookup.Provider。 */
    public static Function<HolderLookup.Provider, WorldDimensions> wrap(
            Function<HolderLookup.Provider, WorldDimensions> dimensions) {
        if (!shouldReplace()) return dimensions;
        return access -> dimensions.apply(access)
                .replaceOverworldGenerator(access, vanillaOverworldGenerator(access));
    }
    //?} else {
    /** 1.21.1 的维度函数参数 / 替换 API 使用 RegistryAccess。 */
    public static Function<RegistryAccess, WorldDimensions> wrap(
            Function<RegistryAccess, WorldDimensions> dimensions) {
        if (!shouldReplace()) return dimensions;
        return access -> dimensions.apply(access)
                .replaceOverworldGenerator(access, vanillaOverworldGenerator(access));
    }
    //?}

    /** 原版主世界生成器（与"默认"世界类型的基座一致）。 */
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
