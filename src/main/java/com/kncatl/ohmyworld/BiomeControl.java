package com.kncatl.ohmyworld;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Function;
import java.util.function.IntBinaryOperator;
import java.util.function.Supplier;
import java.util.stream.Stream;

import com.kncatl.ohmyworld.compat.LevelHeights;
import com.kncatl.ohmyworld.compat.ResourceIds;
import com.kncatl.ohmyworld.mixin.ChunkGeneratorBiomeSourceAccessor;
import com.kncatl.ohmyworld.mixin.ChunkMapAccessor;
import com.google.common.base.Suppliers;
import com.mojang.logging.LogUtils;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeGenerationSettings;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.FeatureSorter;
import net.minecraft.world.level.biome.FixedBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists;
import net.minecraft.world.level.biome.TheEndBiomeSource;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import org.slf4j.Logger;

/**
 * {@code [biome:...]} 指令的运行时套用：
 * <ul>
 *   <li>{@code vanilla} → 该维度的原版群系源（主世界/下界=双噪声预设，末地=末地群系源；自定义维度退回主世界预设）；</li>
 *   <li>{@code <群系id>} → {@link FixedBiomeSource} 单一群系。</li>
 * </ul>
 *
 * <p><b>换群系即装饰照常（用户拍板语义）</b>：有群系规则时，装饰特性跟随新群系生成
 * （树/花草/矿物按群系；超平坦基座也一样）；{@code [features:none]} 可关闭。
 * 为此要同步处理四件事：
 * <ol>
 *   <li>换群系源（{@code biomeSource}，只影响新生成区块）；</li>
 *   <li>超平坦维度的气候采样修复——原版对非噪声生成器用 {@code NoiseGeneratorSettings.dummy()}
 *       构建 RandomState，气候恒定会让双噪声源退化成单一群系；{@code vanilla} 时替换成该维度
 *       真实噪声设置的 RandomState；</li>
 *   <li>放行“配置群系裁剪”——超平坦的 {@code adjustGenerationSettings} 只对配置群系（平原）
 *       去掉特性；换群系后包一层 {@code holder -> holder.value().getGenerationSettings()}，
 *       让所有群系返回原版设置；</li>
 *   <li>重建装饰索引表 {@code featuresPerStep}（构造时按旧群系源 memoize；不重建则特性循环
 *       要么空转要么索引缺项）。按“新旧群系集合并集 + 当前设置 getter”重建，热加载换向也安全。</li>
 * </ol>
 * 另外重建结构组状态（{@code ChunkMap.chunkGeneratorState}），否则群系专属结构组
 * （沙漠神殿、丛林神庙…）不会解锁。规则移除时四件套逐一还原——未写指令的维度零行为变化。
 *
 * <p>热加载：{@link #reapplyAll(MinecraftServer)} 在每次公式重载后重套（含还原）。
 */
public final class BiomeControl {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 首次套用时记录的“原始四件套”；规则移除时还原。WeakHashMap：生成器回收后条目自动消失。 */
    private record State(BiomeSource source,
                         Function<Holder<Biome>, BiomeGenerationSettings> settingsGetter,
                         Supplier<List<FeatureSorter.StepFeatureData>> featuresPerStep,
                         RandomState randomState) {}

    private static final Map<ChunkGenerator, State> STATES = new WeakHashMap<>();

    private BiomeControl() {}

    /** 按该生成器当前的 {@code [biome:...]} 规则 / biome 行套用群系相关状态（无规则 = 不接管 / 还原）。 */
    public static void apply(ServerLevel level, ChunkGenerator generator) {
        DimensionRules.BiomeRule rule = PatternData.biomeRuleFor(generator);
        List<BiomeLayerDef> biomeLayers = PatternData.biomeLayersFor(generator);
        DimensionRules.BiomeFallback biomeFallback = PatternData.biomeFallbackFor(generator);
        boolean featuresOff = PatternData.suppressFeatures(generator);
        synchronized (STATES) {
            ChunkGeneratorBiomeSourceAccessor accessor = (ChunkGeneratorBiomeSourceAccessor) generator;
            State state = STATES.computeIfAbsent(generator, g -> new State(g.getBiomeSource(),
                    accessor.ohmyworld$getGenerationSettingsGetter(),
                    accessor.ohmyworld$getFeaturesPerStep(),
                    level.getChunkSource().randomState()));

            BiomeSource targetSource = state.source();
            RandomState targetRandom = state.randomState();
            Function<Holder<Biome>, BiomeGenerationSettings> targetGetter = state.settingsGetter();
            Supplier<List<FeatureSorter.StepFeatureData>> targetFeatures = state.featuresPerStep();

            if (rule != null || !biomeLayers.isEmpty()) {
                BiomeSource built = !biomeLayers.isEmpty()
                        ? buildFormulaSource(level, generator, biomeLayers, biomeFallback)
                        : build(level, rule);
                if (built == null) return; // 构建失败：已记日志，保持现状
                // 需要真实气候采样的两种情形：vanilla 换源；公式源带 2d/3d 回退（回退要采样原版）。
                boolean needsClimate = rule != null ? rule.vanilla()
                        : biomeFallback != DimensionRules.BiomeFallback.NONE;
                if (needsClimate && !(generator instanceof NoiseBasedChunkGenerator)) {
                    // 超平坦等非噪声生成器：换成真实气候采样，双噪声群系源（含回退）才能形成分布
                    RandomState climate = buildClimateRandomState(level);
                    if (climate != null) targetRandom = climate;
                }
                if (!featuresOff) {
                    // 装饰跟随新群系：放行配置群系裁剪 + 按新群系重建特性索引表
                    targetGetter = biome -> biome.value().getGenerationSettings();
                    // 用"当前被替换的群系源"（而非最初源）参与并集：热加载换向时
                    // 在途区块的旧群系也不会出现索引缺项
                    targetFeatures = rebuildFeatures(built, generator.getBiomeSource(), targetGetter);
                    targetSource = built;
                } else {
                    targetSource = built;
                }
            }

            boolean changed = false;
            if (generator.getBiomeSource() != targetSource) {
                accessor.ohmyworld$setBiomeSource(targetSource);
                changed = true;
            }
            if (level.getChunkSource().randomState() != targetRandom) {
                ((ChunkMapAccessor) level.getChunkSource().chunkMap).ohmyworld$setRandomState(targetRandom);
                changed = true;
            }
            if (accessor.ohmyworld$getGenerationSettingsGetter() != targetGetter) {
                accessor.ohmyworld$setGenerationSettingsGetter(targetGetter);
                changed = true;
            }
            if (accessor.ohmyworld$getFeaturesPerStep() != targetFeatures) {
                accessor.ohmyworld$setFeaturesPerStep(targetFeatures);
                changed = true;
            }
            if (changed) {
                // 结构组在 ChunkMap 构造期按当时的群系源过滤过一次：换源后重建，
                // 让新群系的专属结构（沙漠神殿、丛林神庙…）真正解锁；还原时同样重建。
                rebuildStructureState(level, generator, targetSource);
            }
            if (OhMyWorldConfig.debugLogsEnabled() && changed) {
                String label = rule != null ? rule.toString()
                        : biomeLayers.isEmpty() ? "restored"
                        : "formula(" + biomeLayers.size() + " layer(s), fallback=" + biomeFallback + ")";
                LOGGER.info("ohmyworld: biome of {} -> {} (featuresOff={})",
                        ResourceIds.keyIdString(level.dimension()), label, featuresOff);
            }
        }
    }

    /**
     * 按“新旧群系集合并集 + 当前设置 getter”重建装饰特性索引表。
     * 并集是为了热加载换向时的在途区块（旧群系已填、新表生成）不出现索引缺项。
     * {@code FeatureSorter.buildFeaturesPerStep} 与 ChunkGenerator 构造一致（布尔=true）。
     */
    private static Supplier<List<FeatureSorter.StepFeatureData>> rebuildFeatures(
            BiomeSource newSource, BiomeSource oldSource,
            Function<Holder<Biome>, BiomeGenerationSettings> getter) {
        List<Holder<Biome>> biomes = new ArrayList<>(newSource.possibleBiomes());
        for (Holder<Biome> biome : oldSource.possibleBiomes()) {
            if (!biomes.contains(biome)) biomes.add(biome);
        }
        List<Holder<Biome>> fixed = List.copyOf(biomes);
        return Suppliers.memoize(() -> FeatureSorter.buildFeaturesPerStep(
                fixed, biome -> getter.apply(biome).features(), true));
    }

    /** 热加载：对每个被本模组改过群系相关状态的生成器按最新规则重套（规则已消失的还原）。 */
    public static void reapplyAll(MinecraftServer server) {
        List<ChunkGenerator> generators;
        synchronized (STATES) {
            generators = new ArrayList<>(STATES.keySet());
        }
        for (ChunkGenerator generator : generators) {
            ServerLevel level = findLevel(server, generator);
            if (level != null) apply(level, generator); // 未加载的生成器随世界卸载，等 GC 即可
        }
    }

    /**
     * 群系源变更后重建结构组状态：原版在 ChunkMap 构造期按当时的群系源过滤过一次结构组
     * （{@code createForFlat/Normal} 的 {@code hasBiomesForStructureSet}），不重建的话
     * 群系专属结构组不会解锁；还原群系源时同样重建。失败只记日志。
     */
    private static void rebuildStructureState(ServerLevel level, ChunkGenerator generator, BiomeSource source) {
        try {
            ChunkGeneratorStructureState current = level.getChunkSource().getGeneratorState();
            HolderLookup<StructureSet> structureSets =
                    level.registryAccess().lookupOrThrow(Registries.STRUCTURE_SET);
            RandomState randomState = level.getChunkSource().randomState();
            long seed = current.getLevelSeed();
            ChunkGeneratorStructureState rebuilt;
            if (generator instanceof FlatLevelSource flat) {
                // 与 FlatLevelSource.createState 一致：覆盖表（旧世界）优先，否则全部结构组
                var overrides = flat.settings().structureOverrides();
                Stream<Holder<StructureSet>> base = overrides.isPresent()
                        ? overrides.get().stream()
                        : structureSets.listElements().map(entry -> (Holder<StructureSet>) entry);
                rebuilt = ChunkGeneratorStructureState.createForFlat(randomState, seed,
                        //? >=26.3 {
                        generator.getOrigin(randomState),
                        //?}
                        source, base);
            } else {
                rebuilt = ChunkGeneratorStructureState.createForNormal(randomState, seed,
                        //? >=26.3 {
                        generator.getOrigin(randomState),
                        //?}
                        source, structureSets);
            }
            ((ChunkMapAccessor) level.getChunkSource().chunkMap).ohmyworld$setChunkGeneratorState(rebuilt);
            rebuilt.ensureStructuresGenerated();
        } catch (Exception e) {
            LOGGER.error("ohmyworld: failed to rebuild structure state for {}: {}",
                    ResourceIds.keyIdString(level.dimension()), e.toString());
        }
    }

    /**
     * 依据 biome 行构建公式群系源。2d/3d 的回退用该维度自己的原版群系源（主世界/下界
     * 双噪声、末地末地源）；2d 的参考高度取公式地形的表面（含水面，与预览一致）。
     * 未知群系 id 会在构造期抛错，这里记日志并保持现状。
     */
    private static BiomeSource buildFormulaSource(ServerLevel level, ChunkGenerator generator,
                                                  List<BiomeLayerDef> biomeLayers,
                                                  DimensionRules.BiomeFallback fallbackMode) {
        try {
            BiomeSource fallbackSource = null;
            IntBinaryOperator referenceY = null;
            if (fallbackMode != DimensionRules.BiomeFallback.NONE) {
                fallbackSource = build(level, DimensionRules.BiomeRule.VANILLA);
                if (fallbackSource == null) return null;
            }
            if (fallbackMode == DimensionRules.BiomeFallback.TWO_D) {
                PatternData.PatternSnapshot snapshot = PatternData.snapshotFor(generator);
                if (snapshot == null) {
                    LOGGER.error("ohmyworld: no formula snapshot for {} while building biome source",
                            ResourceIds.keyIdString(level.dimension()));
                    return null;
                }
                int minY = LevelHeights.minY(level);
                int maxY = LevelHeights.maxY(level);
                referenceY = (x, z) -> PatternData.getBaseHeight(snapshot, x, z,
                        Heightmap.Types.WORLD_SURFACE_WG, minY, maxY);
            }
            return new FormulaBiomeSource(level, biomeLayers, fallbackSource,
                    fallbackMode == DimensionRules.BiomeFallback.TWO_D, referenceY);
        } catch (Exception e) {
            LOGGER.error("ohmyworld: failed to build formula biome source for {}: {}",
                    ResourceIds.keyIdString(level.dimension()), e.toString());
            return null;
        }
    }

    /** 依据规则构建群系源；无法构建（未知群系等）返回 null 并记日志。 */
    private static BiomeSource build(ServerLevel level, DimensionRules.BiomeRule rule) {
        try {
            var registryAccess = level.registryAccess();
            if (rule.vanilla()) {
                if (ResourceIds.sameKey(level.dimension(), Level.END)) {
                    return TheEndBiomeSource.create(registryAccess.lookupOrThrow(Registries.BIOME));
                }
                var preset = ResourceIds.sameKey(level.dimension(), Level.NETHER)
                        ? MultiNoiseBiomeSourceParameterLists.NETHER
                        : MultiNoiseBiomeSourceParameterLists.OVERWORLD;
                return MultiNoiseBiomeSource.createFromPreset(
                        registryAccess.lookupOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST)
                                .getOrThrow(preset));
            }
            return new FixedBiomeSource(registryAccess.lookupOrThrow(Registries.BIOME)
                    .getOrThrow(biomeKey(rule.singleBiomeId())));
        } catch (Exception e) {
            LOGGER.error("ohmyworld: failed to build biome source ({}) for {}: {}",
                    rule, ResourceIds.keyIdString(level.dimension()), e.toString());
            return null;
        }
    }

    /** 用该维度真实噪声设置构建 RandomState（气候采样）；失败返回 null 并记日志。 */
    private static RandomState buildClimateRandomState(ServerLevel level) {
        try {
            var key = ResourceIds.sameKey(level.dimension(), Level.NETHER) ? NoiseGeneratorSettings.NETHER
                    : ResourceIds.sameKey(level.dimension(), Level.END) ? NoiseGeneratorSettings.END
                    : NoiseGeneratorSettings.OVERWORLD;
            var noiseGetter = level.registryAccess().lookupOrThrow(Registries.NOISE);
            var settings = level.registryAccess().lookupOrThrow(Registries.NOISE_SETTINGS)
                    .getOrThrow(key).value();
            //? >=26.3 {
            return RandomState.create(noiseGetter, level.getSeed(), settings);
            //?} else {
            return RandomState.create(settings, noiseGetter, level.getSeed());
            //?}
        } catch (Exception e) {
            LOGGER.error("ohmyworld: failed to build climate RandomState for {}: {}",
                    ResourceIds.keyIdString(level.dimension()), e.toString());
            return null;
        }
    }

    /** "命名空间:名称"（缺省命名空间=minecraft）→ 群系键。 */
    static ResourceKey<Biome> biomeKey(String biomeId) {
        String namespace = "minecraft";
        String path = biomeId;
        int colon = biomeId.indexOf(':');
        if (colon >= 0) {
            namespace = biomeId.substring(0, colon);
            path = biomeId.substring(colon + 1);
        }
        return ResourceKey.create(Registries.BIOME, ResourceIds.of(namespace, path));
    }

    private static ServerLevel findLevel(MinecraftServer server, ChunkGenerator generator) {
        for (ServerLevel level : server.getAllLevels()) {
            if (level.getChunkSource().getGenerator() == generator) return level;
        }
        return null;
    }
}
