package com.kncatl.ohmyworld;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicLong;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraft.world.level.levelgen.Heightmap;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

public class PatternData {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    private static final int HEIGHT_CACHE_LIMIT = 8192;

    private static volatile PatternSnapshot currentSnapshot;
    private static final AtomicLong SNAPSHOT_VERSION = new AtomicLong();
    private static final Map<HeightKey, Integer> HEIGHT_CACHE = new LinkedHashMap<>(256, 0.75f, true);
    private static final Map<FlatLevelSource, PatternSnapshot> GENERATOR_PATTERNS = new WeakHashMap<>();
    private static volatile boolean active;
    private static volatile boolean pending;
    /** 客户端刚通过创建界面新建了世界（createFreshLevel 已触发且选中 flat_plus） */
    private static volatile boolean created;

    /** 默认公式（与 OhMyWorldConfig 共用同一份定义）。最底层统一为世界底部 -64。 */
    public static final String DEFAULT_INPUT = "y=-64: minecraft:bedrock;y=-63..64: (x+z)%2==0 ? minecraft:white_concrete : minecraft:gray_concrete";

    public record PatternSnapshot(List<Object> layers, String rawInput, long version) {}

    private record HeightKey(long version, int x, int z, Heightmap.Types type, int minY, int maxY) {}

    public static void set(List<?> pattern, String rawInput) {
        List<Object> copy = List.copyOf(pattern);
        currentSnapshot = new PatternSnapshot(copy, stripNewlines(rawInput), SNAPSHOT_VERSION.incrementAndGet());
        active = !copy.isEmpty();
        clearHeightCache();
    }

    public static boolean setIfValid(FormulaParser.ParseResult result, String rawInput) {
        if (result == null || !result.errors().isEmpty() || result.layers().isEmpty()) return false;
        set(result.layers(), rawInput);
        return true;
    }

    public static PatternSnapshot snapshot() {
        PatternSnapshot snapshot = currentSnapshot;
        if (snapshot != null && !snapshot.layers().isEmpty()) return snapshot;

        synchronized (PatternData.class) {
            snapshot = currentSnapshot;
            if (snapshot == null || snapshot.layers().isEmpty()) {
                List<Object> defaults = FormulaParser.parse(DEFAULT_INPUT);
                snapshot = new PatternSnapshot(defaults, DEFAULT_INPUT, SNAPSHOT_VERSION.incrementAndGet());
                currentSnapshot = snapshot;
                clearHeightCache();
            }
            return snapshot;
        }
    }

    public static List<Object> get() { return snapshot().layers(); }

    public static String getRawInput() { return snapshot().rawInput(); }

    public static boolean isActive() { return active; }

    public static void clearActive() { active = false; }

    /** 将当前快照绑定到具体 FlatLevelSource，避免不同维度的 generator 互相污染。 */
    public static void bindGenerator(FlatLevelSource generator) {
        if (generator == null || !active) return;
        synchronized (GENERATOR_PATTERNS) {
            GENERATOR_PATTERNS.put(generator, snapshot());
        }
    }

    public static void bindAllGenerators() {
        if (!active) return;
        PatternSnapshot snapshot = snapshot();
        synchronized (GENERATOR_PATTERNS) {
            GENERATOR_PATTERNS.replaceAll((generator, ignored) -> snapshot);
        }
    }

    public static PatternSnapshot snapshotFor(FlatLevelSource generator) {
        synchronized (GENERATOR_PATTERNS) {
            return GENERATOR_PATTERNS.get(generator);
        }
    }

    public static void clearGenerator(FlatLevelSource generator) {
        if (generator == null) return;
        synchronized (GENERATOR_PATTERNS) {
            GENERATOR_PATTERNS.remove(generator);
        }
    }

    public static void clearAllGenerators() {
        synchronized (GENERATOR_PATTERNS) {
            GENERATOR_PATTERNS.clear();
        }
    }

    public static void setPending() { pending = true; }

    public static boolean isPending() { return pending; }
    public static void clearPending() { pending = false; }

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
                writeAtomically(marker, getRawInput());
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
                    FormulaParser.ParseResult result = FormulaParser.parseWithErrors(raw);
                    if (!result.errors().isEmpty() || result.layers().isEmpty()) {
                        for (String e : result.errors()) LOGGER.error("ohmyworld: marker formula error: {}", e);
                        return false;
                    }
                    set(result.layers(), raw);
                    active = true;
                    pending = false;
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
        //? >=1.21.5 {
        int minY = chunk.getMinY();
        int maxY = chunk.getMaxY();
        //?} else {
        int minY = chunk.getMinBuildHeight();
        int maxY = chunk.getMaxBuildHeight();
        //?}

        int lo = Math.max(minY, minLayerStart(layers));
        int hi = Math.min(maxY - 1, maxLayerEnd(layers));
        if (lo > hi) return;

        int height = Math.addExact(Math.subtractExact(hi, lo), 1);
        BlockState[] prepared = new BlockState[Math.multiplyExact(height, 16 * 16)];
        Arrays.fill(prepared, AIR);

        for (Object obj : layers) {
            if (obj instanceof FormulaLayerDef f) {
                prepareRange(prepared, lo, height, cx, cz, f.yStart(), f.yEnd(), minY, maxY,
                        (x, z, y) -> f.getBlock(x, z, y));
            } else if (obj instanceof CyclicLayerDef c) {
                prepareRange(prepared, lo, height, cx, cz, c.yStart(), c.yEnd(), minY, maxY,
                        (x, z, y) -> c.getBlock(x, z, y));
            }
        }

        for (int y = lo; y <= hi; y++) {
            int yIndex = (y - lo) * 16 * 16;
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    BlockState st = prepared[yIndex + x * 16 + z];
                    //? >=1.21.5 {
                    // 与原版 FlatLevelSource 一致：无标志填充，避免生成期触发额外光照/方块更新开销
                    chunk.setBlockState(pos.set(x, y, z), st);
                    //?} else {
                    chunk.setBlockState(pos.set(x, y, z), st, false);
                    //?}
                    h0.update(x, y, z, st);
                    h1.update(x, y, z, st);
                }
            }
        }
    }

    private static void prepareRange(BlockState[] prepared, int baseY, int height, int cx, int cz,
                                     int yStart, int yEnd, int minY, int maxY, BlockGetter getter) {
        int lo = Math.max(yStart, Math.max(minY, baseY));
        int hi = Math.min(yEnd, Math.min(maxY - 1, baseY + height - 1));
        for (int y = lo; y <= hi; y++) {
            int yIndex = (y - baseY) * 16 * 16;
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    prepared[yIndex + x * 16 + z] = getter.get(cx + x, cz + z, y);
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

    @FunctionalInterface
    private interface BlockGetter {
        BlockState get(int x, int z, int y);
    }

    private static String stripNewlines(String s) {
        if (s == null) return "";
        return s.replace("\r", "").replace("\n", "");
    }
}
