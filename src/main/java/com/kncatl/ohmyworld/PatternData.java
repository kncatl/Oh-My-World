package com.kncatl.ohmyworld;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicLong;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.TerrainAdjustment;
import net.minecraft.world.level.levelgen.structure.pools.JigsawJunction;
import net.minecraft.world.level.ChunkPos;

import com.kncatl.ohmyworld.compat.BiomeViews;
import com.kncatl.ohmyworld.compat.ChunkWrites;
import com.kncatl.ohmyworld.compat.LevelHeights;
import com.kncatl.ohmyworld.compat.ResourceIds;
import com.kncatl.ohmyworld.expr.ExprEvaluator;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

public class PatternData {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    private static final int HEIGHT_CACHE_LIMIT = 8192;

    /** 维度 → 公式快照；只含显式定义了公式的维度（别名与目标共享同一快照）。空表=未设置过公式。 */
    private static volatile Map<ResourceKey<Level>, PatternSnapshot> DIMENSION_PATTERNS = Map.of();
    /** 当前公式的原始整串输入（编辑器 / marker 用）；null = 未设置过公式（编辑器显示默认公式）。 */
    private static volatile String currentRawInput;
    /** 未设置过公式时的默认快照（未编辑的 flat_plus 世界按默认图案生成主世界）。 */
    private static volatile PatternSnapshot defaultSnapshot;

    private static final AtomicLong SNAPSHOT_VERSION = new AtomicLong();
    private static final Map<HeightKey, Integer> HEIGHT_CACHE = new LinkedHashMap<>(256, 0.75f, true);

    /** 叠加高度诊断日志（debug_logs 时最多 10 条，用于排查结构起点的异常高度）。 */
    private static final java.util.concurrent.atomic.AtomicInteger OVERLAY_HEIGHT_LOG =
            new java.util.concurrent.atomic.AtomicInteger();

    /** 性能诊断（debug_logs）：分阶段累计耗时，每 128 个区块输出一次均值。 */
    private static final AtomicLong PERF_FILL_CHUNKS = new AtomicLong();
    private static final AtomicLong PERF_FILL_PREPARE = new AtomicLong();
    private static final AtomicLong PERF_FILL_SURFACE = new AtomicLong();
    private static final AtomicLong PERF_FILL_WRITE = new AtomicLong();
    private static final AtomicLong PERF_OVERLAY_CHUNKS = new AtomicLong();
    private static final AtomicLong PERF_OVERLAY_COPY = new AtomicLong();
    private static final AtomicLong PERF_OVERLAY_LAYERS = new AtomicLong();
    private static final AtomicLong PERF_OVERLAY_SURFACE = new AtomicLong();
    /** 一次性高度图自检（1.3.4 性能优化时验证 ProtoChunk 的自动高度图更新）。 */
    private static final java.util.concurrent.atomic.AtomicBoolean FIRST_HEIGHT_CHECK =
            new java.util.concurrent.atomic.AtomicBoolean();

    /** 叠加模式的结构高度查询：按列缓存「原版噪声列 + 叠加公式」的结果（1.3.4）。 */
    private static final int OVERLAY_COLUMN_CACHE_LIMIT = 1024;
    private static final Map<OverlayColumnKey, OverlayColumn> OVERLAY_COLUMN_CACHE =
            new LinkedHashMap<>(256, 0.75f, true);

    /**
     * 取原版列时的 ThreadLocal 旁路：{@code getBaseColumn} 的 mixin 看到它就直接放行，
     * 避免在叠加列求值里再次进入叠加分支（递归）。
     */
    private static final ThreadLocal<Boolean> VANILLA_COLUMN_BYPASS = ThreadLocal.withInitial(() -> false);

    /**
     * 生成器 → 公式绑定。binding 里带上了维度键：P3 起热加载会按维度重新解析公式，
     * 非主世界的公式接管也依赖它。WeakHashMap：生成器被回收后绑定自动消失。
     */
    private static final Map<ChunkGenerator, GeneratorBinding> GENERATOR_PATTERNS = new WeakHashMap<>();

    /** 绑定表是否非空；未绑定任何生成器时让 {@link #snapshotFor} 走零开销快路径。 */
    private static volatile boolean anyGeneratorBound;

    /**
     * 生成器 → 共享气候视图（M3）：只在该维度公式用到 climate() 时登记；
     * 与生成器同生命周期（WeakHashMap）。热加载不会改变视图（RandomState 与公式无关）。
     */
    private static final Map<ChunkGenerator, ExprEvaluator.VanillaView> VANILLA_VIEWS = new WeakHashMap<>();

    /**
     * 叠加 + {@code [carvers:vanilla-ew]}：H1 之后公式水体的位置位图。
     * 装饰阶段结束后据此清除原版 carver/spring/lush 池等额外放的水。
     * 以 ChunkAccess 为键（弱引用），清理后立即移除；只在对应模式下登记。
     */
    private static final Map<ChunkAccess, BitSet> OVERLAY_WATER_MASKS =
            Collections.synchronizedMap(new WeakHashMap<>());

    /** 一条绑定：该生成器属于哪个维度、生效哪份公式。 */
    public record GeneratorBinding(ResourceKey<Level> dimension, PatternSnapshot snapshot) {}

    private static volatile boolean active;
    private static volatile boolean pending;
    /** 客户端刚通过创建界面新建了世界（createFreshLevel 已触发且选中 flat_plus） */
    private static volatile boolean created;

    /** 默认公式（与 OhMyWorldConfig 共用同一份定义）。最底层统一为世界底部 -64。 */
    public static final String DEFAULT_INPUT = "y=-64: minecraft:bedrock;y=-63..64: (x+z)%2==0 ? minecraft:white_concrete : minecraft:gray_concrete";

    /** 规范维度名 → 维度键（解析器不依赖 MC 类，映射放在这里）。 */
    private static final Map<String, ResourceKey<Level>> DIMENSION_KEYS = Map.of(
            FormulaParser.DIM_OVERWORLD, Level.OVERWORLD,
            FormulaParser.DIM_NETHER, Level.NETHER,
            FormulaParser.DIM_END, Level.END);

    public record PatternSnapshot(List<Object> layers, String rawInput, long version,
                                  DimensionRules.StructureRule structure, DimensionRules.BiomeRule biome,
                                  boolean featuresOff, List<BiomeLayerDef> biomeLayers,
                                  List<SurfaceLayerDef> surfaceLayers,
                                  DimensionRules.BiomeFallback biomeFallback, boolean carversVanilla,
                                  boolean carversEw,
                                  boolean usesVanillaData, boolean overlay,
                                  DimensionRules.SurfaceMode surfaceMode,
                                  boolean protectStructures) {}

    private record HeightKey(long version, int x, int z, Heightmap.Types type, int minY, int maxY) {}
    /** 叠加求值后的整列（结构高度查询用）。 */
    public record OverlayColumn(int minY, BlockState[] states) {}
    private record OverlayColumnKey(long version, int x, int z, int minY, int height) {}

    /**
     * 设置按维度的公式（旧输入 = 仅 overworld）。有错误或没有任何维度时不生效、返回 false。
     * 别名与目标共享同一份快照；每个快照有独立的版本号（高度缓存按版本隔离）。
     */
    public static boolean setDimensions(FormulaParser.DimensionParseResult result, String rawInput) {
        if (result == null || !result.errors().isEmpty() || result.dimensions().isEmpty()) return false;
        // 编辑器往返要保留原始换行（多行公式显示）；写 marker 时再剥掉换行（见 markActive）
        String raw = rawInput == null ? "" : rawInput;
        Map<FormulaParser.ParsedDimension, PatternSnapshot> shared = new IdentityHashMap<>();
        Map<ResourceKey<Level>, PatternSnapshot> table = new LinkedHashMap<>();
        for (Map.Entry<String, FormulaParser.ParsedDimension> entry : result.dimensions().entrySet()) {
            ResourceKey<Level> dimension = DIMENSION_KEYS.get(entry.getKey());
            if (dimension == null) continue;
            FormulaParser.ParsedDimension parsed = entry.getValue();
            PatternSnapshot snapshot = shared.get(parsed);
            if (snapshot == null) {
                snapshot = new PatternSnapshot(List.copyOf(parsed.layers()), raw,
                        SNAPSHOT_VERSION.incrementAndGet(), parsed.structure(), parsed.biome(),
                        parsed.featuresOff(), List.copyOf(parsed.biomeLayers()),
                        List.copyOf(parsed.surfaceLayers()), parsed.biomeFallback(),
                        parsed.carvers() == DimensionRules.CarversMode.VANILLA
                                || parsed.carvers() == DimensionRules.CarversMode.VANILLA_EXCEPT_WATER,
                        parsed.carvers() == DimensionRules.CarversMode.VANILLA_EXCEPT_WATER,
                        FormulaParser.usesVanillaData(parsed), parsed.overlay(), parsed.surfaceMode(),
                        parsed.protectStructures());
                shared.put(parsed, snapshot);
            }
            table.put(dimension, snapshot);
        }
        if (table.isEmpty()) return false;
        DIMENSION_PATTERNS = Map.copyOf(table);
        currentRawInput = raw;
        active = true;
        clearHeightCache();
        return true;
    }

    /** 该维度当前是否会被公式接管；未设置过公式时默认公式只作用于主世界。 */
    public static boolean hasFormulaFor(ResourceKey<Level> dimension) {
        if (currentRawInput == null) return Level.OVERWORLD.equals(dimension);
        return DIMENSION_PATTERNS.containsKey(dimension);
    }

    /** 该维度是否处于 [terrain:vanilla] 叠加模式（绑定期用来校验生成器类型）。 */
    public static boolean overlayFor(ResourceKey<Level> dimension) {
        return patternFor(dimension).overlay();
    }

    /** 是否显式设置过公式（false = 编辑器显示默认公式、世界未编辑）。 */
    public static boolean hasExplicitFormula() { return currentRawInput != null; }

    public static String getRawInput() {
        String raw = currentRawInput;
        return raw != null ? raw : DEFAULT_INPUT;
    }

    /** 该维度要绑定的快照；null = 不接管（原版生成）。 */
    private static PatternSnapshot patternFor(ResourceKey<Level> dimension) {
        PatternSnapshot snapshot = DIMENSION_PATTERNS.get(dimension);
        if (snapshot != null) return snapshot;
        // 未设置过公式：仅主世界有隐式默认公式（未编辑的 flat_plus 世界按默认图案生成）
        if (currentRawInput == null && Level.OVERWORLD.equals(dimension)) return defaultSnapshot();
        return null;
    }

    private static PatternSnapshot defaultSnapshot() {
        PatternSnapshot snapshot = defaultSnapshot;
        if (snapshot != null) return snapshot;
        synchronized (PatternData.class) {
            if (defaultSnapshot == null) {
                defaultSnapshot = new PatternSnapshot(FormulaParser.parse(DEFAULT_INPUT), DEFAULT_INPUT,
                        SNAPSHOT_VERSION.incrementAndGet(), DimensionRules.StructureRule.ALL, null, false,
                        List.of(), List.of(), DimensionRules.BiomeFallback.NONE, false, false, false, false,
                        DimensionRules.SurfaceMode.VANILLA, true);
            }
            return defaultSnapshot;
        }
    }

    public static boolean isActive() { return active; }

    public static void clearActive() { active = false; }

    /**
     * 将该维度的生成器登记进绑定表（快照 = 该维度当前公式）。该维度没有公式时不
     * 登记——未登记 = 不接管 = 原版生成。绑定表对任何生成器类型通用（超平坦或噪声）。
     */
    public static void bindGenerator(ResourceKey<Level> dimension, ChunkGenerator generator) {
        if (generator == null || !active) return;
        PatternSnapshot snapshot = patternFor(dimension);
        if (snapshot == null) return;
        synchronized (GENERATOR_PATTERNS) {
            GENERATOR_PATTERNS.put(generator, new GeneratorBinding(dimension, snapshot));
            anyGeneratorBound = true;
        }
    }

    /** 热加载：按每条绑定记录的维度取新快照；该维度已无公式的绑定直接移除。 */
    public static void bindAllGenerators() {
        if (!active) return;
        synchronized (GENERATOR_PATTERNS) {
            var iterator = GENERATOR_PATTERNS.entrySet().iterator();
            while (iterator.hasNext()) {
                var entry = iterator.next();
                PatternSnapshot snapshot = patternFor(entry.getValue().dimension());
                if (snapshot == null) {
                    iterator.remove();
                } else {
                    entry.setValue(new GeneratorBinding(entry.getValue().dimension(), snapshot));
                }
            }
            anyGeneratorBound = !GENERATOR_PATTERNS.isEmpty();
        }
    }

    /** 登记/清除某生成器的共享气候视图（null = 清除）。 */
    public static void bindVanillaView(ChunkGenerator generator, ExprEvaluator.VanillaView view) {
        if (generator == null) return;
        synchronized (VANILLA_VIEWS) {
            if (view == null) VANILLA_VIEWS.remove(generator);
            else VANILLA_VIEWS.put(generator, view);
        }
    }

    /** 该生成器的气候视图；未登记 → null（climate() 返回 0）。 */
    public static ExprEvaluator.VanillaView vanillaViewFor(ChunkGenerator generator) {
        synchronized (VANILLA_VIEWS) {
            return VANILLA_VIEWS.get(generator);
        }
    }

    /** 该生成器的公式是否用到需要原版数据的功能（climate 等；决定是否要建视图）。 */
    public static boolean usesVanillaDataFor(ChunkGenerator generator) {
        PatternSnapshot snapshot = snapshotFor(generator);
        return snapshot != null && snapshot.usesVanillaData();
    }

    public static PatternSnapshot snapshotFor(ChunkGenerator generator) {
        if (!anyGeneratorBound) return null;
        synchronized (GENERATOR_PATTERNS) {
            GeneratorBinding binding = GENERATOR_PATTERNS.get(generator);
            return binding == null ? null : binding.snapshot();
        }
    }

    /** [biome:...] 规则；未绑定 / 无规则 → null（群系源还原）。 */
    public static DimensionRules.BiomeRule biomeRuleFor(ChunkGenerator generator) {
        PatternSnapshot snapshot = snapshotFor(generator);
        return snapshot == null ? null : snapshot.biome();
    }

    /** 公式群系层（biome 行）；未绑定 / 无 → 空表（不使用公式群系）。 */
    public static List<BiomeLayerDef> biomeLayersFor(ChunkGenerator generator) {
        PatternSnapshot snapshot = snapshotFor(generator);
        return snapshot == null ? List.of() : snapshot.biomeLayers();
    }

    /** [biome-fallback:...]；未绑定 / 无 → none。 */
    public static DimensionRules.BiomeFallback biomeFallbackFor(ChunkGenerator generator) {
        PatternSnapshot snapshot = snapshotFor(generator);
        return snapshot == null ? DimensionRules.BiomeFallback.NONE : snapshot.biomeFallback();
    }

    /** [carvers:vanilla]（含 vanilla-ew）是否生效；未绑定 / 缺省 → false。 */
    public static boolean carversVanillaFor(ChunkGenerator generator) {
        PatternSnapshot snapshot = snapshotFor(generator);
        return snapshot != null && snapshot.carversVanilla();
    }

    /** [carvers:vanilla-ew]（except water）是否生效；未绑定 / 缺省 → false。 */
    public static boolean carversEwFor(ChunkGenerator generator) {
        PatternSnapshot snapshot = snapshotFor(generator);
        return snapshot != null && snapshot.carversEw();
    }

    /** [features:none] 是否生效；未绑定 / 缺省 → false（零行为变化）。 */
    public static boolean suppressFeatures(ChunkGenerator generator) {
        PatternSnapshot snapshot = snapshotFor(generator);
        return snapshot != null && snapshot.featuresOff();
    }

    /**
     * 生成期结构过滤：[structure:...] 指令按“该生成器所属维度”的规则裁剪结构组。
     * 未绑定 / 规则为 all / 旧公式（无指令）→ 原样返回（零行为变化）。
     */
    public static List<Holder<StructureSet>> filterStructureSets(ChunkGenerator generator,
                                                                 List<Holder<StructureSet>> sets) {
        PatternSnapshot snapshot = snapshotFor(generator);
        if (snapshot == null) return sets;
        DimensionRules.StructureRule rule = snapshot.structure();
        if (rule == null || rule.isDefault()) return sets;

        List<Holder<StructureSet>> filtered = new ArrayList<>(sets.size());
        for (Holder<StructureSet> holder : sets) {
            StructureSet set = holder.value();
            String setId = holder.unwrapKey()
                    .map(key -> DimensionRules.normalizeName(ResourceIds.keyIdString(key))).orElse("");
            List<String> memberIds = new ArrayList<>(set.structures().size());
            for (StructureSet.StructureSelectionEntry entry : set.structures()) {
                entry.structure().unwrapKey().ifPresent(
                        key -> memberIds.add(DimensionRules.normalizeName(ResourceIds.keyIdString(key))));
            }
            if (rule.allows(setId, memberIds)) filtered.add(holder);
        }
        return filtered;
    }

    public static void clearGenerator(ChunkGenerator generator) {
        if (generator == null) return;
        synchronized (GENERATOR_PATTERNS) {
            GENERATOR_PATTERNS.remove(generator);
            anyGeneratorBound = !GENERATOR_PATTERNS.isEmpty();
        }
        synchronized (VANILLA_VIEWS) {
            VANILLA_VIEWS.remove(generator);
        }
    }

    public static void clearAllGenerators() {
        synchronized (GENERATOR_PATTERNS) {
            GENERATOR_PATTERNS.clear();
            anyGeneratorBound = false;
        }
        synchronized (VANILLA_VIEWS) {
            VANILLA_VIEWS.clear();
        }
    }

    public static void setPending() { pending = true; }

    public static boolean isPending() { return pending; }
    public static void clearPending() { pending = false; }

    /**
     * 把当前世界的种子转给求值器（公式内置变量 {@code seed} 与 {@code seedhash} 的来源）。
     * 各维度同值；单元测试可直接调用 {@code ExprEvaluator.setWorldSeed}。
     */
    public static void setWorldSeed(long seed) {
        ExprEvaluator.setWorldSeed(seed);
    }

    /**
     * 把世界出生点（出生区块中心）转给求值器（公式内置变量 {@code spawnx}/{@code spawnz}
     * 的来源）。各维度同值；单元测试可直接调用 {@code ExprEvaluator.setWorldSpawn}。
     */
    public static void setWorldSpawn(int x, int z) {
        ExprEvaluator.setWorldSpawn(x, z);
    }

    public static void setCreated() { created = true; }
    public static boolean isCreated() { return created; }
    public static void clearCreated() { created = false; }

    private static Path markerPath(ServerLevel level) {
        return level.getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                .resolve("ohmyworld_marker.txt");
    }

    /** 激活图案并写入 marker（仅当 marker 不存在时）。overwrite=true 时无条件覆写（server_mode 下配置公式为准）。 */
    public static void markActive(ServerLevel level, boolean overwrite) {
        active = true;
        pending = false;
        created = false;
        try {
            Path marker = markerPath(level);
            if (overwrite || !Files.exists(marker)) {
                Files.createDirectories(marker.getParent());
                // marker 必须保留换行：// 行内注释以换行为界，剥掉换行会让注释吞掉后续内容
                // （1.3.2-beta.3 起支持 // 注释；此前"按单行存储"的写法会造成重新打开
                //  世界时 Formula is empty）。解析侧会先按行剥注释、再合并换行。
                String raw = FormulaParser.normalizeLineBreaks(getRawInput());
                writeAtomically(marker, raw);
                if (OhMyWorldConfig.debugLogsEnabled()) {
                    LOGGER.info("ohmyworld: wrote formula marker ({} chars)", raw.length());
                }
            }
        } catch (Exception e) {
            LOGGER.warn("markActive failed", e);
        }
    }

    public static void markActive(ServerLevel level) {
        markActive(level, false);
    }

    public static boolean restoreFromMarker(ServerLevel level) {
        try {
            Path marker = markerPath(level);
            if (Files.isSymbolicLink(marker)) {
                LOGGER.warn("ohmyworld: refusing to read symbolic-link marker {}", marker);
                return false;
            }
            if (Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.size(marker) > FormulaParser.MAX_INPUT_LENGTH) {
                    LOGGER.error("ohmyworld: marker formula exceeds the maximum input size");
                    return false;
                }
                String raw = Files.readString(marker);
                if (raw != null && !raw.isBlank()) {
                    FormulaParser.DimensionParseResult result = FormulaParser.parseDimensionsWithErrors(raw);
                    if (!result.errors().isEmpty() || result.dimensions().isEmpty()) {
                        for (String e : result.errors()) LOGGER.error("ohmyworld: marker formula error: {}", e);
                        if (raw.indexOf('\n') < 0 && raw.contains("//")) {
                            // 旧版 beta 的已知问题：写 marker 时把换行全部剥掉，// 注释吞掉整份公式。
                            // 这类 marker 无法自动恢复——把公式原文（保留换行）写回 marker 即可修复。
                            LOGGER.error("ohmyworld: the marker contains '//' comments but no line breaks"
                                    + " (written by an early beta). Restore the marker file with the original"
                                    + " formula text (line breaks preserved) to repair this world.");
                        }
                        return false;
                    }
                    if (!setDimensions(result, raw)) return false;
                    pending = false;
                    if (OhMyWorldConfig.debugLogsEnabled()) {
                        LOGGER.info("ohmyworld: restored formula from marker ({} dimension(s))",
                                result.dimensions().size());
                    }
                    return true;
                }
            }
        } catch (Exception e) {
            LOGGER.warn("restoreFromMarker failed", e);
        }
        return false;
    }

    /**
     * 先在临时数组中完整计算区块，确认公式没有抛异常后再写入 ChunkAccess，
     * 避免异常时留下半个公式区块。
     */
    /**
     * 填充前把 biomeis 视图设为该区块（读已填充的群系容器）。
     * 区块填充发生在 BIOMES 阶段之后，因此这里读到的群系就是最终值。
     * 表面通道（surface 行）也在此阶段应用（地形铺完之后、雕刻与特征之前）。
     */
    public static void fillChunk(ChunkAccess chunk, PatternSnapshot snapshot,
                                 ExprEvaluator.VanillaView climate) {
        ExprEvaluator.BiomeView savedBiome = ExprEvaluator.biomeView();
        ExprEvaluator.VanillaView savedClimate = ExprEvaluator.vanillaView();
        ExprEvaluator.setBiomeView(biomeViewFor(chunk));
        ExprEvaluator.setVanillaView(climate);
        try {
            fillChunkInternal(chunk, snapshot);
        } finally {
            ExprEvaluator.setBiomeView(savedBiome);
            ExprEvaluator.setVanillaView(savedClimate);
        }
    }

    /**
     * biomeis 的运行期视图：把 (x, y, z) 映射到"已存储群系"再与字面量比较。
     * 区块直接可用（{@code ChunkAccess.getNoiseBiome} 两代都有）；生成期的
     * {@code WorldGenRegion} 按区块查；其他情形返回 null（此时 biomeis 恒为假，
     * 例如编辑器预览）。
     */
    public static ExprEvaluator.BiomeView biomeViewFor(Object levelOrChunk) {
        QuartBiomeSource source;
        if (levelOrChunk instanceof ChunkAccess chunk) {
            source = chunk::getNoiseBiome;
        } else if (levelOrChunk instanceof WorldGenRegion region) {
            source = (qx, qy, qz) -> {
                try {
                    ChunkAccess chunk = region.getChunk((qx << 2) >> 4, (qz << 2) >> 4);
                    return chunk != null ? chunk.getNoiseBiome(qx, qy, qz) : null;
                } catch (RuntimeException e) {
                    return null;
                }
            };
        } else {
            return null;
        }
        return (x, y, z, biomeId) -> {
            Holder<Biome> holder = source.getNoiseBiome(
                    QuartPos.fromBlock(x), QuartPos.fromBlock(y), QuartPos.fromBlock(z));
            return holder != null && holder.unwrapKey()
                    .map(key -> ResourceIds.keyIdString(key).equals(biomeId))
                    .orElse(false);
        };
    }

    /** 群系查询的最小接口（{@code BiomeManager.NoiseBiomeSource} 在 26.3 已不存在）。 */
    @FunctionalInterface
    private interface QuartBiomeSource {
        Holder<Biome> getNoiseBiome(int qx, int qy, int qz);
    }

    /**
     * 叠加模式（[terrain:vanilla]，M3.5）：原版地形写完（H1）后，用公式层做后处理。
     *
     * <p>语义（第九章 §9.3）：{@code vanilla} = H1 前的原版方块快照；{@code keep} =
     * 此前各层合成后的当前方块；表达式结果与当前值相同则不写；后写覆盖先写。
     * 高度图按写入增量更新；新放置/移除的水与岩浆标记生成后处理。
     */
    public static void overlayChunk(ChunkAccess chunk, PatternSnapshot snapshot,
                                    ExprEvaluator.VanillaView vanillaView) {
        if (snapshot.layers().isEmpty()) return;
        ExprEvaluator.BiomeView savedBiome = ExprEvaluator.biomeView();
        ExprEvaluator.VanillaView savedVanilla = ExprEvaluator.vanillaView();
        ExprEvaluator.setBiomeView(biomeViewFor(chunk));
        ExprEvaluator.setVanillaView(vanillaView);
        try {
            overlayChunkInternal(chunk, snapshot);
            if (snapshot.carversEw()) rememberOverlayWater(chunk);
        } finally {
            ExprEvaluator.setBiomeView(savedBiome);
            ExprEvaluator.setVanillaView(savedVanilla);
        }
    }

    /** H1 后记录公式水体（plain water）位置；仅叠加 + vanilla-ew 使用。 */
    private static void rememberOverlayWater(ChunkAccess chunk) {
        int minY = LevelHeights.minY(chunk);
        int height = chunk.getHeight();
        BitSet bits = new BitSet(height * 256);
        LevelChunkSection[] sections = chunk.getSections();
        for (int y = minY; y < minY + height; y++) {
            LevelChunkSection section = sections[chunk.getSectionIndex(y)];
            if (section == null || section.hasOnlyAir()) continue;
            int localY = y & 15;
            int base = (y - minY) * 256;
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    if (ChunkWrites.getSectionBlock(section, x, localY, z).is(Blocks.WATER)) {
                        bits.set(base + x * 16 + z);
                    }
                }
            }
        }
        OVERLAY_WATER_MASKS.put(chunk, bits);
    }

    /**
     * 装饰阶段结束：叠加 + vanilla-ew 时清除公式水体之外的 plain water。
     *
     * <p>覆盖原版 carver 含水层（正常路径已关）、spring_water、lush caves
     * clay pool 等所有"装饰阶段放的水"；公式自己在 H1 写的水由位图保留。
     * 非叠加 / 非 vanilla-ew / 无位图 → 零行为变化。
     */
    public static void cleanupOverlayFeatureWater(ChunkAccess chunk) {
        BitSet bits = OVERLAY_WATER_MASKS.remove(chunk);
        if (bits == null) return;
        int minY = LevelHeights.minY(chunk);
        int height = chunk.getHeight();
        LevelChunkSection[] sections = chunk.getSections();
        BlockState air = Blocks.AIR.defaultBlockState();
        for (int y = minY; y < minY + height; y++) {
            LevelChunkSection section = sections[chunk.getSectionIndex(y)];
            if (section == null || section.hasOnlyAir()) continue;
            int localY = y & 15;
            int base = (y - minY) * 256;
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    if (!ChunkWrites.getSectionBlock(section, x, localY, z).is(Blocks.WATER)) continue;
                    if (bits.get(base + x * 16 + z)) continue;
                    //? >=1.21.5 {
                    chunk.setBlockState(new BlockPos(chunk.getPos().getMinBlockX() + x, y,
                            chunk.getPos().getMinBlockZ() + z), air, 0);
                    //?} else {
                    chunk.setBlockState(new BlockPos(chunk.getPos().getMinBlockX() + x, y,
                            chunk.getPos().getMinBlockZ() + z), air, false);
                    //?}
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void overlayChunkInternal(ChunkAccess chunk, PatternSnapshot snapshot) {
        List<Object> layers = snapshot.layers();
        boolean perf = OhMyWorldConfig.debugLogsEnabled();
        long perfT0 = perf ? System.nanoTime() : 0L;
        int writes = 0;
        Heightmap h0 = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR_WG);
        Heightmap h1 = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
        int cx = chunk.getPos().getMinBlockX();
        int cz = chunk.getPos().getMinBlockZ();
        int minY = LevelHeights.minY(chunk);
        int maxY = LevelHeights.maxY(chunk);

        // H1 快照：分节拷贝（null 分节 = 全空气；直接写入不会改动这份拷贝）
        LevelChunkSection[] sections = chunk.getSections();
        PalettedContainer<BlockState>[] vanilla = new PalettedContainer[sections.length];
        for (int i = 0; i < sections.length; i++) {
            if (sections[i] != null) vanilla[i] = sections[i].getStates().copy();
        }

        // 叠加视图（sy/sw 列量与 vis/vsolid/vfluid/vair 快照谓词）在此区块求值期间生效
        BoundingBox protection = snapshot.protectStructures() ? protectionBox(chunk) : null;
        SnapshotOverlayView overlayView = new SnapshotOverlayView(minY, vanilla, chunk, protection);
        ExprEvaluator.OverlayView savedOverlay = ExprEvaluator.overlayView();
        ExprEvaluator.setOverlayView(overlayView);
        long perfT1 = perf ? System.nanoTime() : 0L;

        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos firstWrite = new BlockPos.MutableBlockPos();
        boolean hasFirstWrite = false;
        try {
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    int worldX = cx + x;
                    int worldZ = cz + z;
                    // 后写覆盖先写：按层序逐层求值、就地写入
                    for (Object obj : layers) {
                        int lo;
                        int hi;
                        if (obj instanceof FormulaLayerDef f) {
                            if (f.hasWindow()) {
                                // y=surf(a..b)：以该列 sy（含水面）为基准，逐列计算范围
                                int base = overlayView.sy(worldX, worldZ);
                                lo = base + f.windowStart();
                                hi = base + f.windowEnd();
                            } else {
                                lo = f.resolvedStart(minY);
                                hi = f.yEnd();
                            }
                        } else if (obj instanceof CyclicLayerDef c) {
                            lo = c.resolvedStart(minY);
                            hi = c.yEnd();
                        } else {
                            continue;
                        }
                        lo = Math.max(lo, minY);
                        hi = Math.min(hi, maxY - 1);
                        for (int y = lo; y <= hi; y++) {
                            Object result = obj instanceof FormulaLayerDef f
                                    ? f.evalResult(worldX, worldZ, y, minY)
                                    : ((CyclicLayerDef) obj).evalResult(worldX, worldZ, y, minY);
                            if (result == ExprEvaluator.SURFACE_KEEP) continue;
                            BlockState target = result == ExprEvaluator.VANILLA
                                    ? overlayView.stateAt(worldX, y, worldZ)
                                    : result instanceof BlockState st ? st : null;
                            if (target == null) continue;
                            LevelChunkSection section = sections[chunk.getSectionIndex(y)];
                            int localY = y & 15;
                            BlockState current = ChunkWrites.getSectionBlock(section, x, localY, z);
                            if (target == current) continue;
                            // [overlay:protect-structures]（默认开）：只保留原版"实心地面"。
                            // 目标为空气/水时照常写入——否则结构上方的原版水体/陆地会被冻结，
                            // 表现为浮空水或突出水面的地形柱（叠加模式实测问题）。
                            if (protection != null && protection.isInside(worldX, y, worldZ)
                                    && isSurfaceSolid(target) && isSurfaceSolid(current)) {
                                continue;
                            }
                            boolean wasFluid = !current.getFluidState().isEmpty();
                            ChunkWrites.setSectionBlock(section, x, localY, z, target);
                            h0.update(x, y, z, target);
                            h1.update(x, y, z, target);
                            if (wasFluid || !target.getFluidState().isEmpty()) {
                                ChunkWrites.markForPostProcessing(chunk, pos.set(worldX, y, worldZ));
                            }
                            writes++;
                            if (!hasFirstWrite) {
                                hasFirstWrite = true;
                                firstWrite.set(worldX, y, worldZ);
                            }
                        }
                    }
                }
            }
            long perfT2 = perf ? System.nanoTime() : 0L;
            // H3：表面补铺（[surface:vanilla+patch] 接原版之后；[surface:none] 原版已取消）
            if (snapshot.surfaceMode() != DimensionRules.SurfaceMode.VANILLA) {
                try {
                    applyOverlaySurface(chunk, snapshot, overlayView, protection, minY, maxY);
                } finally {
                    ExprEvaluator.clearSurfaceValues();
                }
            }
            if (perf) {
                recordOverlayPerf(perfT0, perfT1, perfT2, System.nanoTime());
            }
        } finally {
            ExprEvaluator.setOverlayView(savedOverlay);
        }
        if (OhMyWorldConfig.debugLogsEnabled() && DEBUG_LOGGED.compareAndSet(false, true)) {
            // 空叠加（y=..: vanilla）时写入数必须为 0——见验收（与原版逐方块一致）
            LOGGER.info("ohmyworld: overlay writes in first processed chunk {} (first write at {} -> {})",
                    writes, firstWrite, hasFirstWrite ? chunk.getBlockState(firstWrite) : "n/a");
        }
    }

    /**
     * 叠加模式的表面补铺（H3；M3.5）：在 H1 之后按 surface 行扫列（读当前区块方块）。
     * {@code curis} 读到的就是当前（补铺进行中）方块；{@code sd/sdb/wd/slope} 语义与
     * flat 表面通道一致。slope 的边框列按 H1 前快照近似（不读邻区块）。
     * 仅在 [surface:vanilla+patch]（接原版之后）或 [surface:none]（原版被取消后）执行。
     */
    private static void applyOverlaySurface(ChunkAccess chunk, PatternSnapshot snapshot,
                                            SnapshotOverlayView overlayView, BoundingBox protection,
                                            int minY, int maxY) {
        List<SurfaceLayerDef> rows = snapshot.surfaceLayers();
        if (rows.isEmpty()) return;
        int cx = chunk.getPos().getMinBlockX();
        int cz = chunk.getPos().getMinBlockZ();
        Heightmap h0 = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR_WG);
        Heightmap h1 = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        // 行范围：扫描只需覆盖各行区间的并集（sd/sdb/wd 从列顶往下累积）
        int scanLo = Integer.MAX_VALUE;
        for (SurfaceLayerDef line : rows) scanLo = Math.min(scanLo, line.resolvedStart(minY));
        scanLo = Math.max(scanLo, minY);

        // 内部 16×16 列的当前方块一次性读入数组（H1 之后、补铺之前）：后续顶面扫描、
        // sd/sdb 与逐列应用都读数组，避免对同一列反复 chunk.getBlockState。
        int scanHeight = maxY - scanLo;
        BlockState[] states = new BlockState[16 * 16 * scanHeight];
        LevelChunkSection[] sections = chunk.getSections();
        for (int y = scanLo; y < maxY; y++) {
            LevelChunkSection section = sections[chunk.getSectionIndex(y)];
            int localY = y & 15;
            int row = (y - scanLo) * 256;
            for (int x = 0; x < 16; x++) {
                int idx = row + x * 16;
                for (int z = 0; z < 16; z++) {
                    states[idx + z] = ChunkWrites.getSectionBlock(section, x, localY, z);
                }
            }
        }

        // 18×18 顶面（内部列读数组；边框列按 H1 前快照近似）
        int[] top = new int[18 * 18];
        for (int gx = 0; gx < 18; gx++) {
            for (int gz = 0; gz < 18; gz++) {
                int worldX = cx + gx - 1;
                int worldZ = cz + gz - 1;
                boolean interior = gx >= 1 && gx <= 16 && gz >= 1 && gz <= 16;
                int found = minY - 1;
                for (int y = maxY - 1; y >= scanLo; y--) {
                    BlockState state = interior
                            ? surfaceState(states, scanLo, gx - 1, y, gz - 1)
                            : overlayView.stateAt(worldX, y, worldZ);
                    if (isSurfaceSolid(state)) {
                        found = y;
                        break;
                    }
                }
                top[gx * 18 + gz] = found;
            }
        }

        // 每列自上而下应用（与 flat 表面通道同序）
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int gx = x + 1;
                int gz = z + 1;
                int here = top[gx * 18 + gz];
                int slope = Math.max(
                        Math.max(Math.abs(here - top[(gx - 1) * 18 + gz]),
                                Math.abs(here - top[(gx + 1) * 18 + gz])),
                        Math.max(Math.abs(here - top[gx * 18 + (gz - 1)]),
                                Math.abs(here - top[gx * 18 + (gz + 1)])));

                int worldX = cx + x;
                int worldZ = cz + z;
                int sdAbove = 0;
                int fluidRun = 0;
                for (int y = maxY - 1; y >= scanLo; y--) {
                    int index = (y - scanLo) * 256 + x * 16 + z;
                    BlockState state = states[index];
                    boolean fluid = !state.getFluidState().isEmpty();
                    boolean solid = !state.isAir() && !fluid;
                    double wd = fluidRun > 0 ? fluidRun : -1;
                    if (solid) {
                        int sd = sdAbove;
                        for (SurfaceLayerDef line : rows) {
                            if (y < line.resolvedStart(minY) || y > line.resolvedEnd(maxY - 1)) continue;
                            if (sd > line.maxDepth()) continue;
                            int sdb = 0;
                            int lowest = Math.max(scanLo, y - 1 - line.maxDepth());
                            for (int yy = y - 1; yy >= lowest; yy--) {
                                if (isSurfaceSolid(states[(yy - scanLo) * 256 + x * 16 + z])) sdb++;
                                else break;
                            }
                            ExprEvaluator.setSurfaceValues(sd, sdb, wd, slope);
                            Object result = ExprEvaluator.evalToSurface(
                                    line.expression(), worldX, worldZ, y - line.resolvedStart(minY), y);
                            if (result != ExprEvaluator.SURFACE_KEEP && result instanceof BlockState replacement) {
                                if (replacement == state) continue;
                                // [overlay:protect-structures]：部件包围盒内不补铺
                                if (protection != null && protection.isInside(worldX, y, worldZ)) continue;
                                boolean wasFluid = !state.getFluidState().isEmpty();
                                LevelChunkSection section = sections[chunk.getSectionIndex(y)];
                                ChunkWrites.setSectionBlock(section, x, y & 15, z, replacement);
                                h0.update(x, y, z, replacement);
                                h1.update(x, y, z, replacement);
                                if (wasFluid || !replacement.getFluidState().isEmpty()) {
                                    ChunkWrites.markForPostProcessing(chunk, pos.set(worldX, y, worldZ));
                                }
                                states[index] = replacement;
                                state = replacement;
                                fluid = !replacement.getFluidState().isEmpty();
                            }
                        }
                        if (solid) sdAbove++;
                    } else {
                        sdAbove = 0;
                    }
                    fluidRun = fluid ? fluidRun + 1 : 0;
                }
            }
        }
    }

    /** 叠加表面通道的列数组读取（越界 = 空气）。 */
    private static BlockState surfaceState(BlockState[] states, int baseY, int x, int y, int z) {
        int index = (y - baseY) * 256 + x * 16 + z;
        return index < 0 || index >= states.length ? AIR : states[index];
    }

    /** 调试日志：叠加后处理的首次写入计数，只记录一次。 */
    private static final java.util.concurrent.atomic.AtomicBoolean DEBUG_LOGGED =
            new java.util.concurrent.atomic.AtomicBoolean();

    /**
     * 叠加模式的结构保护域（M3.5；1.3.2 调整 Y 外扩；1.3.4 只用部件的"地面层"）：
     * 本区块 structure starts 中所有靠近区块（12 格）的部件的包围盒并集，
     * <b>水平外扩 24、垂直 ±4</b>。
     *
     * <p>1.3.4：垂直范围取每个部件的 {@code box.minY()}（jigsaw 的落地层），不再用
     * {@code box.maxY()}——jigsaw 的 expansion hack 会把部件包围盒向上扩展十几格，
     * 用它做垂直保护会把结构<b>上方</b>整段地形冻结，叠加层就永远降不到结构所在的
     * 高度（叠加模式的结构起点已经按叠加后地形计算，见 {@link #overlayBaseHeight}）。
     * 结构自身的方块在 H1 之后才放置，不需要保护空中的体积；保护"地面带"即可
     * 防止结构被架空/切断。不筛 terrainAdaptation（NONE 部件也纳入，更保守）。
     * 没有部件返回 null。
     */
    private static BoundingBox protectionBox(ChunkAccess chunk) {
        ChunkPos chunkPos = chunk.getPos();
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        boolean found = false;
        for (StructureStart start : chunk.getAllStarts().values()) {
            // 只保护"会做地形适配（Beardifier）"的结构：NONE 结构的部件在 H1 之后才放置，
            // 不需要保护地形；把 NONE 也纳入会把结构预置坐标（如海底遗迹/沉船的 y=90
            // 哨兵）处的原版地形/水体冻结成浮空地块，结构再按实时高度图落到上面
            // （1.3.4 实机复现：海洋群系水面上的地形柱 + 水下遗迹）。
            if (start.getStructure().terrainAdaptation() == TerrainAdjustment.NONE) continue;
            for (StructurePiece piece : start.getPieces()) {
                if (!piece.isCloseToChunk(chunkPos, 12)) continue;
                BoundingBox box = piece.getBoundingBox();
                int groundY = box.minY();
                minX = Math.min(minX, box.minX());
                minY = Math.min(minY, groundY);
                minZ = Math.min(minZ, box.minZ());
                maxX = Math.max(maxX, box.maxX());
                maxY = Math.max(maxY, groundY);
                maxZ = Math.max(maxZ, box.maxZ());
                found = true;
                if (piece instanceof PoolElementStructurePiece pool) {
                    for (JigsawJunction junction : pool.getJunctions()) {
                        int jx = junction.getSourceX();
                        int jz = junction.getSourceZ();
                        if (jx <= chunkPos.getMinBlockX() - 12 || jz <= chunkPos.getMinBlockZ() - 12
                                || jx >= chunkPos.getMaxBlockX() + 12 || jz >= chunkPos.getMaxBlockZ() + 12) {
                            continue;
                        }
                        int jy = junction.getSourceGroundY();
                        minX = Math.min(minX, jx);
                        minY = Math.min(minY, jy);
                        minZ = Math.min(minZ, jz);
                        maxX = Math.max(maxX, jx);
                        maxY = Math.max(maxY, jy);
                        maxZ = Math.max(maxZ, jz);
                    }
                }
            }
        }
        if (!found) return null;
        return new BoundingBox(minX - 24, minY - 4, minZ - 24, maxX + 24, maxY + 4, maxZ + 24);
    }

    /**
     * 叠加视图实现（M3.5）：H1 快照（分节拷贝）上的坐标查询与 sy/sw 列量。
     * sy/sw 用单槽列备忘——叠加处理按列推进，同一列的多次查询几乎总是命中。
     */
    private static final class SnapshotOverlayView implements ExprEvaluator.OverlayView {
        private final int minY;
        private final PalettedContainer<BlockState>[] vanilla;
        private final ChunkAccess chunk;
        private final BoundingBox protection;
        private final BlockPos.MutableBlockPos currentPos = new BlockPos.MutableBlockPos();
        private int memoX = Integer.MIN_VALUE;
        private int memoZ = Integer.MIN_VALUE;
        private int memoSy;
        private int memoSw;

        SnapshotOverlayView(int minY, PalettedContainer<BlockState>[] vanilla, ChunkAccess chunk,
                            BoundingBox protection) {
            this.minY = minY;
            this.vanilla = vanilla;
            this.chunk = chunk;
            this.protection = protection;
        }

        @Override
        public double sdist(int x, int z) {
            if (protection == null) return 1_000_000;
            int dx = Math.max(Math.max(protection.minX() - x, x - protection.maxX()), 0);
            int dz = Math.max(Math.max(protection.minZ() - z, z - protection.maxZ()), 0);
            return Math.sqrt((double) dx * dx + (double) dz * dz);
        }

        /** 快照在该坐标的方块（越界/空分节 = 空气）。 */
        BlockState stateAt(int x, int y, int z) {
            int index = (y - minY) >> 4;
            if (index < 0 || index >= vanilla.length) return AIR;
            PalettedContainer<BlockState> container = vanilla[index];
            if (container == null) return AIR;
            return container.get(x & 15, y & 15, z & 15);
        }

        @Override
        public boolean currentIs(int x, int y, int z, BlockState target) {
            return chunk.getBlockState(currentPos.set(x, y, z)).is(target.getBlock());
        }

        @Override
        public boolean vanillaIs(int x, int y, int z, BlockState target) {
            return stateAt(x, y, z).is(target.getBlock());
        }

        @Override
        public boolean vanillaSolid(int x, int y, int z) {
            return stateAt(x, y, z).isSolid();
        }

        @Override
        public boolean vanillaFluid(int x, int y, int z) {
            return !stateAt(x, y, z).getFluidState().isEmpty();
        }

        @Override
        public boolean vanillaAir(int x, int y, int z) {
            return stateAt(x, y, z).isAir();
        }

        @Override
        public int sy(int x, int z) {
            ensureColumn(x, z);
            return memoSy;
        }

        @Override
        public int sw(int x, int z) {
            ensureColumn(x, z);
            return memoSw;
        }

        /** 自顶向下扫一次：sy = 最高非空气；sw = 最高含流体（无水时同 sy）。 */
        private void ensureColumn(int x, int z) {
            if (x == memoX && z == memoZ) return;
            memoX = x;
            memoZ = z;
            int localX = x & 15;
            int localZ = z & 15;
            boolean syFound = false;
            boolean swFound = false;
            int sy = minY - 1;
            int sw = minY - 1;
            for (int index = vanilla.length - 1; index >= 0 && !swFound; index--) {
                PalettedContainer<BlockState> container = vanilla[index];
                if (container == null) continue;
                for (int localY = 15; localY >= 0 && !swFound; localY--) {
                    BlockState state = container.get(localX, localY, localZ);
                    if (!syFound && !state.isAir()) {
                        sy = minY + (index << 4) + localY;
                        syFound = true;
                    }
                    if (!state.getFluidState().isEmpty()) {
                        sw = minY + (index << 4) + localY;
                        swFound = true;
                    }
                }
            }
            if (!swFound) sw = sy;
            memoSy = sy;
            memoSw = sw;
        }
    }

    /**
     * 叠加视图的"列"实现（1.3.4）：结构高度查询在 NoiseColumn 上求值叠加公式，
     * 没有区块/分节。快照语义与 {@link SnapshotOverlayView} 一致：
     * sy = 最高非空气；sw = 最高流体（无水时同 sy）；sdist 固定 1e6
     * （起点阶段没有结构管理器；H1 实际写入仍用真实保护盒）。
     */
    private static final class ArrayOverlayView implements ExprEvaluator.OverlayView {
        private final int minY;
        private final BlockState[] vanilla;
        private final BlockState[] current;
        private int memoX = Integer.MIN_VALUE;
        private int memoZ = Integer.MIN_VALUE;
        private int memoSy;
        private int memoSw;

        ArrayOverlayView(int minY, BlockState[] vanilla, BlockState[] current) {
            this.minY = minY;
            this.vanilla = vanilla;
            this.current = current;
        }

        BlockState stateAt(int x, int y, int z) {
            int index = y - minY;
            return index < 0 || index >= vanilla.length ? AIR : vanilla[index];
        }

        @Override
        public boolean currentIs(int x, int y, int z, BlockState target) {
            int index = y - minY;
            return index >= 0 && index < current.length && current[index].is(target.getBlock());
        }

        @Override
        public boolean vanillaIs(int x, int y, int z, BlockState target) {
            return stateAt(x, y, z).is(target.getBlock());
        }

        @Override
        public boolean vanillaSolid(int x, int y, int z) {
            return stateAt(x, y, z).isSolid();
        }

        @Override
        public boolean vanillaFluid(int x, int y, int z) {
            return !stateAt(x, y, z).getFluidState().isEmpty();
        }

        @Override
        public boolean vanillaAir(int x, int y, int z) {
            return stateAt(x, y, z).isAir();
        }

        @Override
        public int sy(int x, int z) {
            ensureColumn(x, z);
            return memoSy;
        }

        @Override
        public int sw(int x, int z) {
            ensureColumn(x, z);
            return memoSw;
        }

        @Override
        public double sdist(int x, int z) {
            return 1_000_000;
        }

        /** 自顶向下扫一次：sy = 最高非空气；sw = 最高含流体（无水时同 sy）。 */
        private void ensureColumn(int x, int z) {
            if (x == memoX && z == memoZ) return;
            memoX = x;
            memoZ = z;
            boolean syFound = false;
            boolean swFound = false;
            int sy = minY - 1;
            int sw = minY - 1;
            for (int index = vanilla.length - 1; index >= 0 && (!syFound || !swFound); index--) {
                BlockState state = vanilla[index];
                if (!syFound && !state.isAir()) {
                    sy = minY + index;
                    syFound = true;
                }
                if (!state.getFluidState().isEmpty()) {
                    sw = minY + index;
                    swFound = true;
                }
            }
            if (!swFound) sw = sy;
            memoSy = sy;
            memoSw = sw;
        }
    }

    private static void fillChunkInternal(ChunkAccess chunk, PatternSnapshot snapshot) {
        int minY = LevelHeights.minY(chunk);
        int maxY = LevelHeights.maxY(chunk);
        // 有效层：剔除被后续层完整覆盖的层（flat 后写覆盖先写，被覆盖的层不用求值）
        List<Object> layers = FormulaParser.effectiveFlatLayers(snapshot.layers(), minY, maxY);
        boolean perf = OhMyWorldConfig.debugLogsEnabled();
        long perfT0 = perf ? System.nanoTime() : 0L;
        if (PERF_FILL_CHUNKS.get() == 0L) {
            LOGGER.info("ohmyworld: fill perf instrumentation active (debug={})", perf);
        }
        if (layers.isEmpty()) return;
        int cx = chunk.getPos().getMinBlockX();
        int cz = chunk.getPos().getMinBlockZ();

        // 快路径（2026-10 性能优化）：无 surface 行且各层区间不重叠 → 逐层直写 section，
        // 不分配 prepared 临时数组；高度图在最后按列维护。
        if (snapshot.surfaceLayers().isEmpty()
                && FormulaParser.layersDisjoint(layers, minY, maxY)
                && canFillDirect(layers, minY, maxY)) {
            fillDirect(chunk, layers, minY, maxY);
            if (perf) {
                long t = System.nanoTime();
                if (FIRST_HEIGHT_CHECK.compareAndSet(false, true)) {
                    verifyAutoHeightmaps(chunk, minY, maxY);
                }
                recordFillPerf(perfT0, perfT0, perfT0, t);
            }
            return;
        }

        int lo = Math.max(minY, minLayerStart(layers));
        int hi = Math.min(maxY - 1, maxLayerEnd(layers));
        if (lo > hi) return;

        int height = Math.addExact(Math.subtractExact(hi, lo), 1);
        BlockState[] prepared = new BlockState[Math.multiplyExact(height, 16 * 16)];
        Arrays.fill(prepared, AIR);

        for (Object obj : layers) {
            if (obj instanceof FormulaLayerDef f) {
                prepareFormulaLayer(prepared, lo, height, cx, cz, f, minY, maxY);
            } else if (obj instanceof CyclicLayerDef c) {
                prepareCyclicLayer(prepared, lo, height, cx, cz, c, minY, maxY);
            }
        }
        long perfT1 = perf ? System.nanoTime() : 0L;

        // 表面通道：地形铺完之后、写入区块之前（与"surface → carvers → features"的原版顺序一致）
        if (!snapshot.surfaceLayers().isEmpty()) {
            try {
                applySurface(prepared, lo, height, cx, cz, minY, maxY, snapshot.surfaceLayers(), layers);
            } finally {
                ExprEvaluator.clearSurfaceValues();
            }
        }
        long perfT2 = perf ? System.nanoTime() : 0L;

        LevelChunkSection[] sections = chunk.getSections();
        for (int y = lo; y <= hi; y++) {
            int sectionIndex = chunk.getSectionIndex(y);
            LevelChunkSection section = sections[sectionIndex];
            int localY = y & 15;
            int yIndex = (y - lo) * 16 * 16;
            for (int x = 0; x < 16; x++) {
                int xIndex = yIndex + x * 16;
                for (int z = 0; z < 16; z++) {
                    BlockState st = prepared[xIndex + z];
                    // 新区块本就是空气：跳过空气写入（与原版 FlatLevelSource 一致）。
                    if (st == AIR) continue;
                    // 直接写 section（无锁调色板），高度图在整列写完后统一维护
                    ChunkWrites.setSectionBlock(section, x, localY, z, st);
                }
            }
        }
        updateFillHeightmaps(chunk, prepared, lo, hi);
        if (perf) {
            if (FIRST_HEIGHT_CHECK.compareAndSet(false, true)) {
                verifyAutoHeightmaps(chunk, minY, maxY);
            }
            recordFillPerf(perfT0, perfT1, perfT2, System.nanoTime());
        }
    }

    /**
     * 直接填充只对"求值本身便宜"的层启用：列不变公式层/周期循环层可以在每列只求值
     * 一次；含逐格求值的公式仍走 prepared 路径（列外层遍历对求值器更友好，实测更稳）。
     */
    private static boolean canFillDirect(List<Object> layers, int minY, int maxY) {
        for (Object obj : layers) {
            if (obj instanceof FormulaLayerDef f) {
                if (!f.columnInvariant()) return false;
            } else if (obj instanceof CyclicLayerDef c) {
                int lo = Math.max(c.resolvedStart(minY), minY);
                int hi = Math.min(c.yEnd(), maxY - 1);
                int period = c.columnPeriod();
                if (period <= 0 || period > hi - lo + 1) return false;
            } else {
                return false;
            }
        }
        return true;
    }

    /**
     * flat 直接填充快路径（2026-10 性能优化）：无 surface 行、层区间不重叠时，
     * 逐层把方块直接写进 section，不分配/回读 prepared 临时数组。
     * 每层的列不变表达式只求值一次；循环层按周期缓存。高度图按列在最后维护。
     */
    private static void fillDirect(ChunkAccess chunk, List<Object> layers, int minY, int maxY) {
        int cx = chunk.getPos().getMinBlockX();
        int cz = chunk.getPos().getMinBlockZ();
        LevelChunkSection[] sections = chunk.getSections();
        int[] worldTopY = new int[256];
        int[] floorTopY = new int[256];
        Arrays.fill(worldTopY, Integer.MIN_VALUE);
        Arrays.fill(floorTopY, Integer.MIN_VALUE);
        BlockState[] worldTop = new BlockState[256];
        BlockState[] floorTop = new BlockState[256];

        for (Object obj : layers) {
            if (obj instanceof FormulaLayerDef f) {
                int lo = Math.max(f.resolvedStart(minY), minY);
                int hi = Math.min(f.yEnd(), maxY - 1);
                if (lo > hi) continue;
                if (f.columnInvariant()) {
                    BlockState[] column = new BlockState[256];
                    for (int x = 0; x < 16; x++) {
                        for (int z = 0; z < 16; z++) {
                            column[x * 16 + z] = f.getBlock(cx + x, cz + z, lo, minY);
                        }
                    }
                    for (int y = lo; y <= hi; y++) {
                        LevelChunkSection section = sections[chunk.getSectionIndex(y)];
                        int localY = y & 15;
                        for (int x = 0; x < 16; x++) {
                            for (int z = 0; z < 16; z++) {
                                int idx = x * 16 + z;
                                BlockState st = column[idx];
                                if (st == AIR) continue;
                                section.setBlockState(x, localY, z, st, false);
                                trackTop(idx, y, st, worldTopY, worldTop, floorTopY, floorTop);
                            }
                        }
                    }
                } else {
                    // 列外层遍历：保留求值器的"每列惰性预备"（按 y 外层会让每个格子
                    // 都成为新列，共享 let 退化成逐格重算——实测 4 倍差距）。
                    for (int x = 0; x < 16; x++) {
                        for (int z = 0; z < 16; z++) {
                            int idx = x * 16 + z;
                            LevelChunkSection section = null;
                            for (int y = lo; y <= hi; y++) {
                                if (section == null || (y & 15) == 0) {
                                    section = sections[chunk.getSectionIndex(y)];
                                }
                                BlockState st = f.getBlock(cx + x, cz + z, y, minY);
                                if (st == AIR) continue;
                                section.setBlockState(x, y & 15, z, st, false);
                                trackTop(idx, y, st, worldTopY, worldTop, floorTopY, floorTop);
                            }
                        }
                    }
                }
            } else if (obj instanceof CyclicLayerDef c) {
                int lo = Math.max(c.resolvedStart(minY), minY);
                int hi = Math.min(c.yEnd(), maxY - 1);
                if (lo > hi) continue;
                int rangeLen = hi - lo + 1;
                int period = c.columnPeriod();
                if (period == 0 || period > rangeLen) {
                    for (int x = 0; x < 16; x++) {
                        for (int z = 0; z < 16; z++) {
                            int idx = x * 16 + z;
                            LevelChunkSection section = null;
                            for (int y = lo; y <= hi; y++) {
                                if (section == null || (y & 15) == 0) {
                                    section = sections[chunk.getSectionIndex(y)];
                                }
                                BlockState st = c.getBlock(cx + x, cz + z, y, minY);
                                if (st == AIR) continue;
                                section.setBlockState(x, y & 15, z, st, false);
                                trackTop(idx, y, st, worldTopY, worldTop, floorTopY, floorTop);
                            }
                        }
                    }
                } else {
                    int start = c.resolvedStart(minY);
                    int layerY = lo - start;
                    BlockState[] cycle = new BlockState[256 * period];
                    for (int x = 0; x < 16; x++) {
                        for (int z = 0; z < 16; z++) {
                            int base = (x * 16 + z) * period;
                            for (int pos = 0; pos < period; pos++) {
                                cycle[base + pos] = c.getBlockForPos(cx + x, cz + z, pos, layerY, start);
                            }
                        }
                    }
                    for (int y = lo; y <= hi; y++) {
                        int pos = c.posOf(y, minY);
                        LevelChunkSection section = sections[chunk.getSectionIndex(y)];
                        int localY = y & 15;
                        for (int x = 0; x < 16; x++) {
                            for (int z = 0; z < 16; z++) {
                                int idx = x * 16 + z;
                                BlockState st = cycle[idx * period + pos];
                                if (st == AIR) continue;
                                section.setBlockState(x, localY, z, st, false);
                                trackTop(idx, y, st, worldTopY, worldTop, floorTopY, floorTop);
                            }
                        }
                    }
                }
            }
        }
        updateDirectHeightmaps(chunk, worldTopY, worldTop, floorTopY, floorTop);
    }

    private static void trackTop(int idx, int y, BlockState st, int[] worldTopY, BlockState[] worldTop,
                                 int[] floorTopY, BlockState[] floorTop) {
        if (y > worldTopY[idx] && Heightmap.Types.WORLD_SURFACE_WG.isOpaque().test(st)) {
            worldTopY[idx] = y;
            worldTop[idx] = st;
        }
        if (y > floorTopY[idx] && Heightmap.Types.OCEAN_FLOOR_WG.isOpaque().test(st)) {
            floorTopY[idx] = y;
            floorTop[idx] = st;
        }
    }

    private static void updateDirectHeightmaps(ChunkAccess chunk, int[] worldTopY, BlockState[] worldTop,
                                               int[] floorTopY, BlockState[] floorTop) {
        Heightmap world = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
        Heightmap floor = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR_WG);
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int idx = x * 16 + z;
                if (worldTopY[idx] != Integer.MIN_VALUE) world.update(x, worldTopY[idx], z, worldTop[idx]);
                if (floorTopY[idx] != Integer.MIN_VALUE) floor.update(x, floorTopY[idx], z, floorTop[idx]);
            }
        }
    }

    /**
     * 按列维护填充后的 WORLDGEN 高度图（1.3.4 性能优化：不再逐方块 update）。
     * 高度图语义与原版一致：WORLD_SURFACE = 最高非空气；OCEAN_FLOOR = 最高
     * OCEAN_FLOOR_WG 的谓词。空列保持高度图的初始值（维度最低 y）。
     */
    private static void updateFillHeightmaps(ChunkAccess chunk, BlockState[] prepared, int lo, int hi) {
        Heightmap world = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
        Heightmap floor = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR_WG);
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int worldY = Integer.MIN_VALUE;
                int floorY = Integer.MIN_VALUE;
                BlockState worldState = null;
                BlockState floorState = null;
                for (int y = hi; y >= lo && (worldState == null || floorState == null); y--) {
                    BlockState st = prepared[(y - lo) * 256 + x * 16 + z];
                    if (worldState == null && Heightmap.Types.WORLD_SURFACE_WG.isOpaque().test(st)) {
                        worldY = y;
                        worldState = st;
                    }
                    if (floorState == null && Heightmap.Types.OCEAN_FLOOR_WG.isOpaque().test(st)) {
                        floorY = y;
                        floorState = st;
                    }
                }
                if (worldState != null) world.update(x, worldY, z, worldState);
                if (floorState != null) floor.update(x, floorY, z, floorState);
            }
        }
    }

    /** 一次性验证：高度图（自动或手工维护）与区块实际顶面一致（debug 用）。 */
    private static void verifyAutoHeightmaps(ChunkAccess chunk, int minY, int maxY) {
        Heightmap world = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
        Heightmap floor = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR_WG);
        LevelChunkSection[] sections = chunk.getSections();
        int bad = 0;
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int expectedWorld = minY;
                int expectedFloor = minY;
                boolean worldFound = false;
                boolean floorFound = false;
                for (int y = maxY - 1; y >= minY && (!worldFound || !floorFound); y--) {
                    BlockState st = sections[chunk.getSectionIndex(y)].getBlockState(x, y & 15, z);
                    if (!worldFound && Heightmap.Types.WORLD_SURFACE_WG.isOpaque().test(st)) {
                        expectedWorld = y + 1;
                        worldFound = true;
                    }
                    if (!floorFound && Heightmap.Types.OCEAN_FLOOR_WG.isOpaque().test(st)) {
                        expectedFloor = y + 1;
                        floorFound = true;
                    }
                }
                if (world.getFirstAvailable(x, z) != expectedWorld) bad++;
                if (floor.getFirstAvailable(x, z) != expectedFloor) bad++;
            }
        }
        LOGGER.info("ohmyworld: heightmap auto-update check: {} mismatches", bad);
    }

    /** 性能诊断输出（debug_logs）：fill 各阶段均值（毫秒/区块），每 32 区块一条。 */
    private static void recordFillPerf(long t0, long t1, long t2, long t3) {
        long n = PERF_FILL_CHUNKS.incrementAndGet();
        long prepare = PERF_FILL_PREPARE.addAndGet(t1 - t0);
        long surface = PERF_FILL_SURFACE.addAndGet(t2 - t1);
        long write = PERF_FILL_WRITE.addAndGet(t3 - t2);
        if (n % 32 == 0) {
            LOGGER.info("ohmyworld: fill perf ({} chunks avg): prepare {} ms, surface {} ms,"
                            + " write+heightmap {} ms",
                    n, prepare / 1_000_000.0 / n, surface / 1_000_000.0 / n,
                    write / 1_000_000.0 / n);
        }
    }

    /**
     * 写入一个公式层。
     *
     * <p>若该层不引用 ly（{@link FormulaLayerDef#columnInvariant()}），同一列内所有 y
     * 的结果必然相同：每列只求值一次，再用 {@code arraycopy} 铺满整段高度。
     * 默认公式那类「只按 x/z 取色」的层因此可以少算 (层高 - 1) 倍的表达式。
     */
    private static void prepareFormulaLayer(BlockState[] prepared, int baseY, int height, int cx, int cz,
                                            FormulaLayerDef f, int minY, int maxY) {
        int lo = Math.max(f.yStart(), Math.max(minY, baseY));
        int hi = Math.min(f.yEnd(), Math.min(maxY - 1, baseY + height - 1));
        if (lo > hi) return;

        if (f.columnInvariant()) {
            BlockState[] column = new BlockState[16 * 16];
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    BlockState st = f.getBlock(cx + x, cz + z, lo, minY);
                    column[x * 16 + z] = st;
                }
            }
            for (int y = lo; y <= hi; y++) {
                System.arraycopy(column, 0, prepared, (y - baseY) * 16 * 16, column.length);
            }
            return;
        }

        // 列外层遍历：与 y 无关的绑定由求值器在每列首次求值时预备，逐格只算剩下的部分。
        // 若按 y 外层遍历，每个格子都是一列新的 (x, z)，预备会退化成逐格重算。
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int worldX = cx + x;
                int worldZ = cz + z;
                for (int y = lo; y <= hi; y++) {
                    prepared[(y - baseY) * 16 * 16 + x * 16 + z] = f.getBlock(worldX, worldZ, y, minY);
                }
            }
        }
    }

    /**
     * 写入一个循环层。
     *
     * <p>循环层即使各条目不引用 ly，整层也并非常量——结果按 pos 选取条目。但同一列
     * 的结果以 cycleLength 为周期，因此每列只需算 min(层高, cycleLength) 次，
     * 而不是逐格重算。
     */
    private static void prepareCyclicLayer(BlockState[] prepared, int baseY, int height, int cx, int cz,
                                           CyclicLayerDef c, int minY, int maxY) {
        int lo = Math.max(c.yStart(), Math.max(minY, baseY));
        int hi = Math.min(c.yEnd(), Math.min(maxY - 1, baseY + height - 1));
        if (lo > hi) return;

        int rangeLen = hi - lo + 1;
        int period = c.columnPeriod();
        if (period == 0 || period > rangeLen) {
            // 与 y 相关，或周期比层高还长：整层缓存没有收益，但仍按列遍历——
            // 条目里与 y 无关的绑定可以每列只算一次（理由同公式层）。
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    int worldX = cx + x;
                    int worldZ = cz + z;
                    for (int y = lo; y <= hi; y++) {
                        prepared[(y - baseY) * 16 * 16 + x * 16 + z] = c.getBlock(worldX, worldZ, y, minY);
                    }
                }
            }
            return;
        }

        // 条目都不引用 ly，因此 layerY 取任一值都等价
        int start = c.resolvedStart(minY);
        int layerY = lo - start;
        BlockState[] cycle = new BlockState[period];
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int worldX = cx + x;
                int worldZ = cz + z;
                for (int pos = 0; pos < period; pos++) {
                    cycle[pos] = c.getBlockForPos(worldX, worldZ, pos, layerY, start);
                }
                for (int y = lo; y <= hi; y++) {
                    prepared[(y - baseY) * 16 * 16 + x * 16 + z] = cycle[c.posOf(y, minY)];
                }
            }
        }
    }

    /**
     * 表面通道（1.3.1）：对公式地形（prepared）应用 surface 行。
     *
     * <p>变量定义（冻结）：
     * <ul>
     *   <li>{@code sd}：本方块上方连续实心数（顶面 = 0）；</li>
     *   <li>{@code sdb}：本方块下方连续实心数（扫到该行 maxdepth + 1 为止）；</li>
     *   <li>{@code wd}：无水 = -1；否则本方块上方连续流体块数（≥1）；</li>
     *   <li>{@code slope}：本列顶面高度与四邻列顶面高度的最大绝对差（格距 1）。</li>
     * </ul>
     * 实心 = 非空气且非流体。顶面高度取自**表面修改前**的地形：区块内用 prepared、
     * 四周边框列重复求值公式（不读邻区块——纯函数）。只对实心块、且
     * {@code sd <= 行 maxdepth}、y 在该行范围内的行求值；行按顺序应用，后写覆盖先写；
     * 表达式返回方块时写入（后续行看到修改后的方块），返回 {@code keep} 时保持。
     */
    private static void applySurface(BlockState[] prepared, int baseY, int height, int cx, int cz,
                                     int minY, int maxY, List<SurfaceLayerDef> surfaceLayers,
                                     List<Object> layers) {
        int lo = baseY;
        int hi = baseY + height - 1;

        // 1) 顶面高度图：18×18（含四周一圈边框列），表面修改前
        int[] top = new int[18 * 18];
        for (int gx = 0; gx < 18; gx++) {
            for (int gz = 0; gz < 18; gz++) {
                if (gx >= 1 && gx <= 16 && gz >= 1 && gz <= 16) {
                    int x = gx - 1;
                    int z = gz - 1;
                    int found = minY - 1;
                    for (int y = hi; y >= lo; y--) {
                        if (isSurfaceSolid(prepared[(y - baseY) * 256 + x * 16 + z])) {
                            found = y;
                            break;
                        }
                    }
                    top[gx * 18 + gz] = found;
                } else {
                    top[gx * 18 + gz] = formulaTopSolid(layers, cx + gx - 1, cz + gz - 1, minY, maxY);
                }
            }
        }

        // 2) 每列自上而下应用
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int gx = x + 1;
                int gz = z + 1;
                int here = top[gx * 18 + gz];
                int slope = Math.max(
                        Math.max(Math.abs(here - top[(gx - 1) * 18 + gz]),
                                Math.abs(here - top[(gx + 1) * 18 + gz])),
                        Math.max(Math.abs(here - top[gx * 18 + (gz - 1)]),
                                Math.abs(here - top[gx * 18 + (gz + 1)])));

                int worldX = cx + x;
                int worldZ = cz + z;
                int sdAbove = 0;
                int fluidRun = 0;
                for (int y = hi; y >= lo; y--) {
                    int index = (y - baseY) * 256 + x * 16 + z;
                    BlockState state = prepared[index];
                    boolean fluid = !state.getFluidState().isEmpty();
                    boolean solid = !state.isAir() && !fluid;
                    double wd = fluidRun > 0 ? fluidRun : -1;
                    if (solid) {
                        int sd = sdAbove;
                        for (SurfaceLayerDef line : surfaceLayers) {
                            if (y < line.resolvedStart(minY) || y > line.resolvedEnd(maxY - 1)) continue;
                            if (sd > line.maxDepth()) continue;
                            int sdb = 0;
                            int lowest = Math.max(lo, y - 1 - line.maxDepth());
                            for (int yy = y - 1; yy >= lowest; yy--) {
                                if (isSurfaceSolid(prepared[(yy - baseY) * 256 + x * 16 + z])) sdb++;
                                else break;
                            }
                            ExprEvaluator.setSurfaceValues(sd, sdb, wd, slope);
                            Object result = ExprEvaluator.evalToSurface(
                                    line.expression(), worldX, worldZ, y - line.resolvedStart(minY), y);
                            if (result != ExprEvaluator.SURFACE_KEEP && result instanceof BlockState replacement) {
                                prepared[index] = replacement;
                                state = replacement;
                                fluid = !replacement.getFluidState().isEmpty();
                            }
                        }
                        sdAbove++;
                    } else {
                        sdAbove = 0;
                    }
                    fluidRun = fluid ? fluidRun + 1 : 0;
                }
            }
        }
    }

    /** 实心 = 非空气且非流体（表面通道用）。 */
    private static boolean isSurfaceSolid(BlockState state) {
        return !state.isAir() && state.getFluidState().isEmpty();
    }

    /** 边框列的顶面高度：重复求值公式（不读邻区块）。无实心 → minY - 1。 */
    private static int formulaTopSolid(List<Object> layers, int x, int z, int minY, int maxY) {
        int upper = Math.min(maxY - 1, maxLayerEnd(layers));
        for (int y = upper; y >= minY; y--) {
            if (isSurfaceSolid(blockAt(layers, x, z, y, minY))) return y;
        }
        return minY - 1;
    }

    /** 按与区块填充相同的顺序计算某个世界坐标最终得到的方块状态（minY 用于解析开区间层的 ly 基准）。 */
    public static BlockState blockAt(List<Object> layers, int x, int z, int y, int dimensionMinY) {
        // 后写覆盖先写：从最后一层往前找，第一个覆盖该 y 的层就是最终结果。
        // 公式层/循环层的结果总是方块（flat 模式的 keep/vanilla 只存在于叠加模式，
        // 不走这里），因此可以提前返回——高度查询/表面边框列因此少算大量层。
        for (int i = layers.size() - 1; i >= 0; i--) {
            Object obj = layers.get(i);
            if (obj instanceof FormulaLayerDef f && y >= f.yStart() && y <= f.yEnd()) {
                return f.getBlock(x, z, y, dimensionMinY);
            }
            if (obj instanceof CyclicLayerDef c && y >= c.yStart() && y <= c.yEnd()) {
                return c.getBlock(x, z, y, dimensionMinY);
            }
        }
        return AIR;
    }

    /** 带 biomeis 视图的基座高度查询（view 为 null 时沿用当前视图）。 */
    public static int getBaseHeight(PatternSnapshot snapshot, int x, int z, Heightmap.Types type,
                                    int minY, int maxY, ExprEvaluator.BiomeView view) {
        if (view == null) return getBaseHeight(snapshot, x, z, type, minY, maxY);
        ExprEvaluator.BiomeView saved = ExprEvaluator.biomeView();
        ExprEvaluator.setBiomeView(view);
        try {
            return getBaseHeight(snapshot, x, z, type, minY, maxY);
        } finally {
            ExprEvaluator.setBiomeView(saved);
        }
    }

    public static BlockState[] buildColumn(PatternSnapshot snapshot, int x, int z, int minY, int total) {
        BlockState[] column = new BlockState[total];
        Arrays.fill(column, AIR);
        if (total == 0) return column;
        int maxY = Math.addExact(minY, total);
        int lo = Math.max(minY, minLayerStart(snapshot.layers()));
        int hi = Math.min(maxY - 1, maxLayerEnd(snapshot.layers()));
        for (int y = lo; y <= hi; y++) column[y - minY] = blockAt(snapshot.layers(), x, z, y, minY);
        return column;
    }

    /** 带 biomeis 视图的整列构建（view 为 null 时沿用当前视图）。 */
    public static BlockState[] buildColumn(PatternSnapshot snapshot, int x, int z, int minY, int total,
                                           ExprEvaluator.BiomeView view) {
        if (view == null) return buildColumn(snapshot, x, z, minY, total);
        ExprEvaluator.BiomeView saved = ExprEvaluator.biomeView();
        ExprEvaluator.setBiomeView(view);
        try {
            return buildColumn(snapshot, x, z, minY, total);
        } finally {
            ExprEvaluator.setBiomeView(saved);
        }
    }

    public static int getBaseHeight(PatternSnapshot snapshot, int x, int z, Heightmap.Types type,
                                    int minY, int maxY) {
        if (maxY <= minY) return minY;
        HeightKey key = new HeightKey(snapshot.version(), x, z, type, minY, maxY);
        synchronized (HEIGHT_CACHE) {
            Integer cached = HEIGHT_CACHE.get(key);
            if (cached != null) return cached;
        }

        int upper = Math.min(maxY - 1, maxLayerEnd(snapshot.layers()));
        int result = minY;
        for (long y = upper; y >= minY; y--) {
            BlockState state = blockAt(snapshot.layers(), x, z, (int) y, minY);
            if (type.isOpaque().test(state)) {
                result = (int) y + 1;
                break;
            }
        }

        synchronized (HEIGHT_CACHE) {
            HEIGHT_CACHE.put(key, result);
            if (HEIGHT_CACHE.size() > HEIGHT_CACHE_LIMIT) {
                HEIGHT_CACHE.remove(HEIGHT_CACHE.keySet().iterator().next());
            }
        }
        return result;
    }

    // ---------------------------------------------------------------------
    // 叠加模式的结构高度（1.3.4）：结构起点必须看到叠加后的地形（设计草案 §9.5）。
    // 原版噪声列 + 叠加公式 → 结果列；按列缓存。已知近似见 PLAN-1.3.4 §2.2：
    // 噪声列不含原版表面材质；sdist 在起点阶段按 1e6 处理（没有结构管理器）。
    // ---------------------------------------------------------------------

    /** {@code getBaseColumn} 的 mixin 旁路：为 true 时直接走原版实现（避免递归）。 */
    public static boolean isVanillaColumnBypass() {
        return VANILLA_COLUMN_BYPASS.get();
    }

    /** 取原版噪声列（不进入叠加分支）。 */
    public static NoiseColumn vanillaBaseColumn(ChunkGenerator generator, int x, int z,
                                                LevelHeightAccessor level, RandomState random) {
        boolean saved = VANILLA_COLUMN_BYPASS.get();
        VANILLA_COLUMN_BYPASS.set(true);
        try {
            return generator.getBaseColumn(x, z, level, random);
        } finally {
            VANILLA_COLUMN_BYPASS.set(saved);
        }
    }

    /** 叠加模式：原版噪声列 + 叠加公式求值后的整列（带 LRU 缓存）。 */
    public static OverlayColumn overlayEvaluatedColumn(PatternSnapshot snapshot, ChunkGenerator generator,
                                                       int x, int z, LevelHeightAccessor level,
                                                       RandomState random) {
        // 先用"关卡高度范围"做缓存键（稳定、无需先构建噪声列）；命中时完全跳过原版列构建。
        int levelMinY = LevelHeights.minY(level);
        int levelHeight = LevelHeights.maxY(level) - levelMinY;
        OverlayColumnKey key = new OverlayColumnKey(snapshot.version(), x, z, levelMinY, levelHeight);
        synchronized (OVERLAY_COLUMN_CACHE) {
            OverlayColumn cached = OVERLAY_COLUMN_CACHE.get(key);
            if (cached != null) return cached;
        }

        NoiseColumn base = vanillaBaseColumn(generator, x, z, level, random);
        // 列的范围与原版 iterateNoiseColumn 一致：生成器噪声设置按本关卡高度裁剪。
        NoiseBasedChunkGenerator noiseGenerator = (NoiseBasedChunkGenerator) generator;
        NoiseSettings noiseSettings = noiseGenerator.generatorSettings().value()
                .noiseSettings().clampToHeightAccessor(level);
        int minY = noiseSettings.minY();
        BlockState[] vanilla = new BlockState[noiseSettings.height()];
        for (int i = 0; i < vanilla.length; i++) {
            vanilla[i] = base.getBlock(minY + i);
        }
        BlockState[] result = vanilla.clone();
        ArrayOverlayView view = new ArrayOverlayView(minY, vanilla, result);
        ExprEvaluator.OverlayView savedOverlay = ExprEvaluator.overlayView();
        ExprEvaluator.BiomeView savedBiome = ExprEvaluator.biomeView();
        ExprEvaluator.VanillaView savedVanilla = ExprEvaluator.vanillaView();
        ExprEvaluator.setOverlayView(view);
        ExprEvaluator.setBiomeView(BiomeViews.view(generator.getBiomeSource(), BiomeViews.sampler(random)));
        ExprEvaluator.setVanillaView(vanillaViewFor(generator));
        try {
            applyOverlayLayersToColumn(snapshot, x, z, minY, result, view);
        } finally {
            ExprEvaluator.setOverlayView(savedOverlay);
            ExprEvaluator.setBiomeView(savedBiome);
            ExprEvaluator.setVanillaView(savedVanilla);
        }

        OverlayColumn column = new OverlayColumn(minY, result);
        synchronized (OVERLAY_COLUMN_CACHE) {
            OVERLAY_COLUMN_CACHE.put(key, column);
            while (OVERLAY_COLUMN_CACHE.size() > OVERLAY_COLUMN_CACHE_LIMIT) {
                OVERLAY_COLUMN_CACHE.remove(OVERLAY_COLUMN_CACHE.keySet().iterator().next());
            }
        }
        return column;
    }

    /** 在单列上按层序求值叠加公式（与 {@code overlayChunkInternal} 同语义）。 */
    private static void applyOverlayLayersToColumn(PatternSnapshot snapshot, int x, int z, int minY,
                                                   BlockState[] result, ArrayOverlayView view) {
        int maxY = minY + result.length - 1;
        for (Object obj : snapshot.layers()) {
            int lo;
            int hi;
            if (obj instanceof FormulaLayerDef f) {
                if (f.hasWindow()) {
                    int base = view.sy(x, z);
                    lo = base + f.windowStart();
                    hi = base + f.windowEnd();
                } else {
                    lo = f.resolvedStart(minY);
                    hi = f.yEnd();
                }
            } else if (obj instanceof CyclicLayerDef c) {
                lo = c.resolvedStart(minY);
                hi = c.yEnd();
            } else {
                continue;
            }
            lo = Math.max(lo, minY);
            hi = Math.min(hi, maxY);
            for (int y = lo; y <= hi; y++) {
                Object value = obj instanceof FormulaLayerDef f
                        ? f.evalResult(x, z, y, minY)
                        : ((CyclicLayerDef) obj).evalResult(x, z, y, minY);
                if (value == ExprEvaluator.SURFACE_KEEP) continue;
                BlockState target = value == ExprEvaluator.VANILLA
                        ? view.stateAt(x, y, z)
                        : value instanceof BlockState state ? state : null;
                if (target == null) continue;
                if (target == result[y - minY]) continue;
                result[y - minY] = target;
            }
        }
    }

    /** 叠加模式的结构起点高度（按 Heightmap.Types 谓词扫描叠加列）。 */
    public static int overlayBaseHeight(PatternSnapshot snapshot, ChunkGenerator generator,
                                        int x, int z, Heightmap.Types type, LevelHeightAccessor level,
                                        RandomState random) {
        HeightKey key = new HeightKey(snapshot.version(), x, z, type,
                LevelHeights.minY(level), LevelHeights.maxY(level));
        synchronized (HEIGHT_CACHE) {
            Integer cached = HEIGHT_CACHE.get(key);
            if (cached != null) return cached;
        }
        OverlayColumn column = overlayEvaluatedColumn(snapshot, generator, x, z, level, random);
        int result = column.minY();
        for (int i = column.states().length - 1; i >= 0; i--) {
            if (type.isOpaque().test(column.states()[i])) {
                result = column.minY() + i + 1;
                break;
            }
        }
        if (OhMyWorldConfig.debugLogsEnabled()
                && type == Heightmap.Types.WORLD_SURFACE_WG && result > 55
                && OVERLAY_HEIGHT_LOG.getAndIncrement() < 10) {
            LOGGER.info("ohmyworld: overlay WORLD_SURFACE height {} at ({}, {}) [snapshot v{}]",
                    result, x, z, snapshot.version());
        }
        synchronized (HEIGHT_CACHE) {
            HEIGHT_CACHE.put(key, result);
            if (HEIGHT_CACHE.size() > HEIGHT_CACHE_LIMIT) {
                HEIGHT_CACHE.remove(HEIGHT_CACHE.keySet().iterator().next());
            }
        }
        return result;
    }

    /** 叠加模式的结构基座列（RuinedPortal / NetherFossil 等 getBaseColumn 调用）。 */
    public static NoiseColumn overlayBaseColumn(PatternSnapshot snapshot, ChunkGenerator generator,
                                                int x, int z, LevelHeightAccessor level, RandomState random) {
        OverlayColumn column = overlayEvaluatedColumn(snapshot, generator, x, z, level, random);
        return new NoiseColumn(column.minY(), column.states().clone());
    }

    /** 该坐标"生效"的方块：非叠加 = 公式层结果；叠加 = 叠加列缓存。 */
    public static BlockState effectiveBlockAt(PatternSnapshot snapshot, ChunkGenerator generator,
                                              int x, int y, int z, LevelHeightAccessor level, RandomState random) {
        if (!snapshot.overlay()) {
            return blockAt(snapshot.layers(), x, z, y, LevelHeights.minY(level));
        }
        OverlayColumn column = overlayEvaluatedColumn(snapshot, generator, x, z, level, random);
        int index = y - column.minY();
        return index < 0 || index >= column.states().length ? AIR : column.states()[index];
    }

    /**
     * 结构起点是否落在水面上（1.3.4，用户拍板：跳过）。
     *
     * <p>判定：该列有水体（WORLD_SURFACE 高于 OCEAN_FLOOR），起点 y 贴着水面
     * （{@code Hw - 1 .. Hw + 1}，覆盖 Jigsaw 的 getFirstFreeHeight 与
     * Structure.getLowestY 的 getFirstOccupiedHeight），且该列最高非空气方块是水。
     * 只对 {@code step = SURFACE_STRUCTURES} 调用（见 MixinStructure）——海底结构
     * （OCEAN_FLOOR 起点）与地下结构天然不在水面高度，不会被跳过；下界熔岩海
     * 因"最高方块不是水"而不触发。
     */
    public static boolean isSurfaceOnWater(PatternSnapshot snapshot, ChunkGenerator generator,
                                           BlockPos pos, LevelHeightAccessor level, RandomState random) {
        int x = pos.getX();
        int z = pos.getZ();
        int worldFree = generator.getFirstFreeHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, level, random);
        int floorFree = generator.getFirstFreeHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, level, random);
        if (worldFree <= floorFree) return false;
        int y = pos.getY();
        if (y < worldFree - 1 || y > worldFree + 1) return false;
        return effectiveBlockAt(snapshot, generator, x, worldFree - 1, z, level, random).is(Blocks.WATER);
    }

    private static int minLayerStart(List<Object> layers) {
        int min = Integer.MAX_VALUE;
        for (Object obj : layers) {
            if (obj instanceof FormulaLayerDef f) min = Math.min(min, f.yStart());
            else if (obj instanceof CyclicLayerDef c) min = Math.min(min, c.yStart());
        }
        return min == Integer.MAX_VALUE ? 0 : min;
    }

    private static int maxLayerEnd(List<Object> layers) {
        int max = Integer.MIN_VALUE;
        for (Object obj : layers) {
            if (obj instanceof FormulaLayerDef f) max = Math.max(max, f.yEnd());
            else if (obj instanceof CyclicLayerDef c) max = Math.max(max, c.yEnd());
        }
        return max == Integer.MIN_VALUE ? -1 : max;
    }

    private static void clearHeightCache() {
        synchronized (HEIGHT_CACHE) {
            HEIGHT_CACHE.clear();
        }
    }

    /** 性能诊断输出（debug_logs）：overlay 各阶段均值（毫秒/区块），每 128 区块一条。 */
    private static void recordOverlayPerf(long t0, long t1, long t2, long t3) {
        long n = PERF_OVERLAY_CHUNKS.incrementAndGet();
        long copy = PERF_OVERLAY_COPY.addAndGet(t1 - t0);
        long layers = PERF_OVERLAY_LAYERS.addAndGet(t2 - t1);
        long surface = PERF_OVERLAY_SURFACE.addAndGet(t3 - t2);
        if (n % 32 == 0) {
            LOGGER.info("ohmyworld: overlay perf ({} chunks avg): snapshot {} ms,"
                            + " layers+writes {} ms, surface {} ms",
                    n, copy / 1_000_000.0 / n, layers / 1_000_000.0 / n,
                    surface / 1_000_000.0 / n);
        }
    }

    private static void writeAtomically(Path target, String content) throws Exception {
        if (Files.isSymbolicLink(target)) {
            throw new IllegalStateException("refusing to replace symbolic-link marker " + target);
        }
        Path parent = target.toAbsolutePath().getParent();
        Path temp = Files.createTempFile(parent, target.getFileName().toString(), ".tmp");
        try {
            Files.writeString(temp, content);
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}
