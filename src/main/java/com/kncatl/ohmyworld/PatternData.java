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
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
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
                                  DimensionRules.StructureRule structure, DimensionRules.BiomeRule biome) {}

    private record HeightKey(long version, int x, int z, Heightmap.Types type, int minY, int maxY) {}

    /**
     * 设置按维度的公式（旧输入 = 仅 overworld）。有错误或没有任何维度时不生效、返回 false。
     * 别名与目标共享同一份快照；每个快照有独立的版本号（高度缓存按版本隔离）。
     */
    public static boolean setDimensions(FormulaParser.DimensionParseResult result, String rawInput) {
        if (result == null || !result.errors().isEmpty() || result.dimensions().isEmpty()) return false;
        String raw = stripNewlines(rawInput);
        Map<FormulaParser.ParsedDimension, PatternSnapshot> shared = new IdentityHashMap<>();
        Map<ResourceKey<Level>, PatternSnapshot> table = new LinkedHashMap<>();
        for (Map.Entry<String, FormulaParser.ParsedDimension> entry : result.dimensions().entrySet()) {
            ResourceKey<Level> dimension = DIMENSION_KEYS.get(entry.getKey());
            if (dimension == null) continue;
            FormulaParser.ParsedDimension parsed = entry.getValue();
            PatternSnapshot snapshot = shared.get(parsed);
            if (snapshot == null) {
                snapshot = new PatternSnapshot(List.copyOf(parsed.layers()), raw,
                        SNAPSHOT_VERSION.incrementAndGet(), parsed.structure(), parsed.biome());
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
                        SNAPSHOT_VERSION.incrementAndGet(), DimensionRules.StructureRule.ALL, null);
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

    public static PatternSnapshot snapshotFor(ChunkGenerator generator) {
        if (!anyGeneratorBound) return null;
        synchronized (GENERATOR_PATTERNS) {
            GeneratorBinding binding = GENERATOR_PATTERNS.get(generator);
            return binding == null ? null : binding.snapshot();
        }
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
    }

    public static void clearAllGenerators() {
        synchronized (GENERATOR_PATTERNS) {
            GENERATOR_PATTERNS.clear();
            anyGeneratorBound = false;
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
                String raw = getRawInput();
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
    public static void fillChunk(ChunkAccess chunk, List<Object> layers) {
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
                    BlockState st = f.getBlock(cx + x, cz + z, lo);
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
                    prepared[(y - baseY) * 16 * 16 + x * 16 + z] = f.getBlock(worldX, worldZ, y);
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
                        prepared[(y - baseY) * 16 * 16 + x * 16 + z] = c.getBlock(worldX, worldZ, y);
                    }
                }
            }
            return;
        }

        // 条目都不引用 ly，因此 layerY 取任一值都等价
        int layerY = lo - c.yStart();
        BlockState[] cycle = new BlockState[period];
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int worldX = cx + x;
                int worldZ = cz + z;
                for (int pos = 0; pos < period; pos++) {
                    cycle[pos] = c.getBlockForPos(worldX, worldZ, pos, layerY);
                }
                for (int y = lo; y <= hi; y++) {
                    prepared[(y - baseY) * 16 * 16 + x * 16 + z] = cycle[c.posOf(y)];
                }
            }
        }
    }

    /** 按与区块填充相同的顺序计算某个世界坐标最终得到的方块状态。 */
    public static BlockState blockAt(List<Object> layers, int x, int z, int y) {
        BlockState result = AIR;
        for (Object obj : layers) {
            if (obj instanceof FormulaLayerDef f && y >= f.yStart() && y <= f.yEnd()) {
                result = f.getBlock(x, z, y);
            } else if (obj instanceof CyclicLayerDef c && y >= c.yStart() && y <= c.yEnd()) {
                result = c.getBlock(x, z, y);
            }
        }
        return result;
    }

    public static BlockState[] buildColumn(PatternSnapshot snapshot, int x, int z, int minY, int total) {
        BlockState[] column = new BlockState[total];
        Arrays.fill(column, AIR);
        if (total == 0) return column;
        int maxY = Math.addExact(minY, total);
        int lo = Math.max(minY, minLayerStart(snapshot.layers()));
        int hi = Math.min(maxY - 1, maxLayerEnd(snapshot.layers()));
        for (int y = lo; y <= hi; y++) column[y - minY] = blockAt(snapshot.layers(), x, z, y);
        return column;
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
            BlockState state = blockAt(snapshot.layers(), x, z, (int) y);
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
