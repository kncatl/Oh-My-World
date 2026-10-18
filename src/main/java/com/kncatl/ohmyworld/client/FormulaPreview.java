package com.kncatl.ohmyworld.client;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.state.BlockState;

import com.kncatl.ohmyworld.DimensionRules;
import com.kncatl.ohmyworld.FormulaParser;
import com.kncatl.ohmyworld.PatternData;
import com.kncatl.ohmyworld.expr.ExprEvaluator;

/**
 * 公式俯视预览：对每列取顶部非空气方块、按地图色着色。
 *
 * <p>纯计算、无副作用，可在后台线程调用；由屏幕侧做防抖、取消与缓存。
 * 采样区域为以 ({@code centerX},{@code centerZ}) 为中心的 {@link #SIZE}×{@link #SIZE} 个采样点，
 * 相邻采样点相距 {@code spacing} 个方块（间距 1 = 逐方块；间距 2/4/8… = 缩小视野看大格局）。
 *
 * <p>性能：采样密度固定为 {@link #SIZE}（旧版在 spacing&gt;1 时翻倍到 72×72，绘制/求值都 ×4）；
 * 顶部方块颜色按 (公式, 种子, 维度, x, z, 高度范围) 做 LRU 缓存，平移/缩放时可复用大部分列。
 */
public final class FormulaPreview {

    /** 采样网格边长（所有缩放级别固定）。 */
    public static final int SIZE = 36;

    /** 顶部颜色缓存上限：约 6 个完整视野。 */
    private static final int CACHE_MAX = 8192;

    /** 空列（全空气）的显示色。 */
    private static final int EMPTY_COLOR = 0xFF141414;

    /** 给定缩放时的采样网格边长；固定 36，避免大缩放时 72×72 的绘制/求值开销。 */
    public static int cellsFor(int spacing) {
        return SIZE;
    }

    public record Result(int[] colors, String dimensionKey, int cells) {}

    private record ColumnKey(long formulaKey, long seed, String dimension,
                             int x, int z, int minY, int total) {}

    private static final Map<ColumnKey, Integer> TOP_COLORS =
            new LinkedHashMap<>(1024, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<ColumnKey, Integer> eldest) {
                    return size() > CACHE_MAX;
                }
            };

    private FormulaPreview() {}

    /**
     * 计算指定维度、指定视野的预览；该维度不在公式里时返回 null。
     *
     * @param formulaKey 公式内容指纹（颜色缓存键）
     * @param seed       世界种子（颜色缓存键；seedhash/噪声依赖它）
     * @param cancelled  返回 true 时尽快中止（拖动/缩放产生新请求时）
     */
    public static Result compute(FormulaParser.DimensionParseResult parsed, String dimension,
                                 int centerX, int centerZ, int spacing,
                                 ExprEvaluator.VanillaView climate,
                                 long formulaKey, long seed, BooleanSupplier cancelled) {
        if (dimension == null) return null;
        FormulaParser.ParsedDimension parsedDim = parsed.dimensions().get(dimension);
        if (parsedDim == null || parsedDim.layers().isEmpty()) return null;

        PatternData.PatternSnapshot snapshot = new PatternData.PatternSnapshot(
                List.copyOf(parsedDim.layers()), "", 0L, DimensionRules.StructureRule.ALL, null, false,
                List.of(), List.of(), DimensionRules.BiomeFallback.NONE, false, false, false, false,
                DimensionRules.SurfaceMode.VANILLA, true);
        // 与各维度实际高度保持一致（超世界 -64 起、384 高；下界/末地 0 起、256 高）
        int minY = FormulaParser.DIM_OVERWORLD.equals(dimension) ? -64 : 0;
        int total = FormulaParser.DIM_OVERWORLD.equals(dimension) ? 384 : 256;
        int step = Math.max(1, spacing);
        int cells = cellsFor(step);
        int origin = -cells / 2;

        // 预览线程上的气候视图（创建界面已加载的世界生成注册表 + 种子框；可为 null）
        ExprEvaluator.setVanillaView(climate);
        try {
            int[] colors = new int[cells * cells];
            for (int dz = 0; dz < cells; dz++) {
                for (int dx = 0; dx < cells; dx++) {
                    if (cancelled != null && cancelled.getAsBoolean()) return null;
                    int worldX = centerX + (origin + dx) * step;
                    int worldZ = centerZ + (origin + dz) * step;
                    ColumnKey key = new ColumnKey(formulaKey, seed, dimension,
                            worldX, worldZ, minY, total);
                    Integer cached;
                    synchronized (TOP_COLORS) {
                        cached = TOP_COLORS.get(key);
                    }
                    if (cached == null) {
                        BlockState[] column = PatternData.buildColumn(snapshot, worldX, worldZ, minY, total);
                        cached = topColor(column);
                        synchronized (TOP_COLORS) {
                            TOP_COLORS.put(key, cached);
                        }
                    }
                    colors[dz * cells + dx] = cached;
                }
            }
            return new Result(colors, dimension, cells);
        } finally {
            ExprEvaluator.setVanillaView(null);
        }
    }

    private static int topColor(BlockState[] column) {
        for (int i = column.length - 1; i >= 0; i--) {
            BlockState state = column[i];
            if (state.isAir()) continue;
            int color = state.getMapColor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).col;
            return (color >>> 24) == 0 ? color | 0xFF000000 : color;
        }
        return EMPTY_COLOR;
    }
}
