package com.kncatl.ohmyworld;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.util.ArrayList;
import java.util.Arrays;
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
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.StructureSet;

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
                                  boolean usesVanillaData, boolean overlay) {}

    private record HeightKey(long version, int x, int z, Heightmap.Types type, int minY, int maxY) {}

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
                        parsed.carvers() == DimensionRules.CarversMode.VANILLA,
                        FormulaParser.usesVanillaData(parsed), parsed.overlay());
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
                        List.of(), List.of(), DimensionRules.BiomeFallback.NONE, false, false, false);
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

    /** [carvers:vanilla] 是否生效；未绑定 / 缺省 → false。 */
    public static boolean carversVanillaFor(ChunkGenerator generator) {
        PatternSnapshot snapshot = snapshotFor(generator);
        return snapshot != null && snapshot.carversVanilla();
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
                // marker 按单行存储（换行只影响公式文本的排版，解析不受影响）
                String raw = stripNewlines(getRawInput());
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
        } finally {
            ExprEvaluator.setBiomeView(savedBiome);
            ExprEvaluator.setVanillaView(savedVanilla);
        }
    }

    @SuppressWarnings("unchecked")
    private static void overlayChunkInternal(ChunkAccess chunk, PatternSnapshot snapshot) {
        List<Object> layers = snapshot.layers();
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
        SnapshotOverlayView overlayView = new SnapshotOverlayView(minY, vanilla);
        ExprEvaluator.OverlayView savedOverlay = ExprEvaluator.overlayView();
        ExprEvaluator.setOverlayView(overlayView);

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
                            pos.set(worldX, y, worldZ);
                            BlockState current = chunk.getBlockState(pos);
                            if (target == current) continue;
                            boolean wasFluid = !current.getFluidState().isEmpty();
                            ChunkWrites.setBlock(chunk, pos, target);
                            h0.update(x, y, z, target);
                            h1.update(x, y, z, target);
                            if (wasFluid || !target.getFluidState().isEmpty()) {
                                ChunkWrites.markForPostProcessing(chunk, pos);
                            }
                            writes++;
                            if (!hasFirstWrite) {
                                hasFirstWrite = true;
                                firstWrite.set(pos);
                            }
                        }
                    }
                }
            }
        } finally {
            ExprEvaluator.setOverlayView(savedOverlay);
        }
        if (OhMyWorldConfig.debugLogsEnabled() && writes > 0
                && OVERLAY_WRITES_LOGGED.compareAndSet(false, true)) {
            LOGGER.info("ohmyworld: overlay writes in first processed chunk {} (first write at {} -> {})",
                    writes, firstWrite, hasFirstWrite ? chunk.getBlockState(firstWrite) : "n/a");
        }
    }

    /** 调试日志：叠加后处理的首次写入计数，只记录一次。 */
    private static final java.util.concurrent.atomic.AtomicBoolean OVERLAY_WRITES_LOGGED =
            new java.util.concurrent.atomic.AtomicBoolean();

    /**
     * 叠加视图实现（M3.5）：H1 快照（分节拷贝）上的坐标查询与 sy/sw 列量。
     * sy/sw 用单槽列备忘——叠加处理按列推进，同一列的多次查询几乎总是命中。
     */
    private static final class SnapshotOverlayView implements ExprEvaluator.OverlayView {
        private final int minY;
        private final PalettedContainer<BlockState>[] vanilla;
        private int memoX = Integer.MIN_VALUE;
        private int memoZ = Integer.MIN_VALUE;
        private int memoSy;
        private int memoSw;

        SnapshotOverlayView(int minY, PalettedContainer<BlockState>[] vanilla) {
            this.minY = minY;
            this.vanilla = vanilla;
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

    private static void fillChunkInternal(ChunkAccess chunk, PatternSnapshot snapshot) {
        List<Object> layers = snapshot.layers();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        Heightmap h0 = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR_WG);
        Heightmap h1 = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
        int cx = chunk.getPos().getMinBlockX();
        int cz = chunk.getPos().getMinBlockZ();
        int minY = LevelHeights.minY(chunk);
        int maxY = LevelHeights.maxY(chunk);

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

        // 表面通道：地形铺完之后、写入区块之前（与"surface → carvers → features"的原版顺序一致）
        if (!snapshot.surfaceLayers().isEmpty()) {
            try {
                applySurface(prepared, lo, height, cx, cz, minY, maxY, snapshot.surfaceLayers(), layers);
            } finally {
                ExprEvaluator.clearSurfaceValues();
            }
        }

        for (int y = lo; y <= hi; y++) {
            int yIndex = (y - lo) * 16 * 16;
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    BlockState st = prepared[yIndex + x * 16 + z];
                    // 新区块本就是空气，写入空气与对应的高度图更新都是空操作。
                    // 自下而上写入时 i 只会因不透明方块前进到 y+1，重扫分支
                    // （i-1 == y）不可能命中，因此跳过是语义等价的。
                    // 对稀疏结构这一步能省掉绝大多数写入与高度图更新。
                    if (st == AIR) continue;
                    // 与原版 FlatLevelSource 一致：无标志填充，避免生成期触发额外光照/方块更新开销
                    ChunkWrites.setBlock(chunk, pos.set(x, y, z), st);
                    h0.update(x, y, z, st);
                    h1.update(x, y, z, st);
                }
            }
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
        BlockState result = AIR;
        for (Object obj : layers) {
            if (obj instanceof FormulaLayerDef f && y >= f.yStart() && y <= f.yEnd()) {
                result = f.getBlock(x, z, y, dimensionMinY);
            } else if (obj instanceof CyclicLayerDef c && y >= c.yStart() && y <= c.yEnd()) {
                result = c.getBlock(x, z, y, dimensionMinY);
            }
        }
        return result;
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

    private static String stripNewlines(String s) {
        if (s == null) return "";
        return s.replace("\r", "").replace("\n", "");
    }
}
