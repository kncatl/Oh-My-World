package com.kncatl.ohmyworld.client;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.state.BlockState;

import com.kncatl.ohmyworld.DimensionRules;
import com.kncatl.ohmyworld.FormulaParser;
import com.kncatl.ohmyworld.PatternData;

/**
 * 公式俯视预览：对每列取顶部非空气方块、按地图色着色。
 *
 * <p>纯计算、无副作用，可在后台线程调用；由屏幕侧做防抖与缓存。
 * 采样区域为以 (0,0) 为中心的 {@link #SIZE}×{@link #SIZE} 列（间距 1 方块），
 * 适合棋盘格/条纹/局部条件这类公式；正弦波等大尺度图案在小窗口内看着较平是预期行为。
 */
public final class FormulaPreview {

    /** 采样网格边长（列数）。 */
    public static final int SIZE = 36;

    /** 采样区域起点（以 (0,0) 为中心）。 */
    private static final int ORIGIN = -SIZE / 2;

    /** 空列（全空气）的显示色。 */
    private static final int EMPTY_COLOR = 0xFF141414;

    public record Result(int[] colors, String dimensionKey) {}

    private FormulaPreview() {}

    /** 计算预览；公式为空或无有效维度时返回 null。 */
    public static Result compute(FormulaParser.DimensionParseResult parsed) {
        String dimension = pickDimension(parsed);
        if (dimension == null) return null;
        FormulaParser.ParsedDimension parsedDim = parsed.dimensions().get(dimension);
        if (parsedDim == null || parsedDim.layers().isEmpty()) return null;

        PatternData.PatternSnapshot snapshot = new PatternData.PatternSnapshot(
                List.copyOf(parsedDim.layers()), "", 0L, DimensionRules.StructureRule.ALL, null, false);
        // 与各维度实际高度保持一致（超世界 -64 起、384 高；下界/末地 0 起、256 高）
        int minY = FormulaParser.DIM_OVERWORLD.equals(dimension) ? -64 : 0;
        int total = FormulaParser.DIM_OVERWORLD.equals(dimension) ? 384 : 256;

        int[] colors = new int[SIZE * SIZE];
        for (int dz = 0; dz < SIZE; dz++) {
            for (int dx = 0; dx < SIZE; dx++) {
                BlockState[] column = PatternData.buildColumn(snapshot, ORIGIN + dx, ORIGIN + dz,
                        minY, total);
                colors[dz * SIZE + dx] = topColor(column);
            }
        }
        return new Result(colors, dimension);
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

    /** 预览维度：优先主世界，其次按固定顺序取第一个有公式的维度。 */
    private static String pickDimension(FormulaParser.DimensionParseResult parsed) {
        for (String dimension : List.of(FormulaParser.DIM_OVERWORLD,
                FormulaParser.DIM_NETHER, FormulaParser.DIM_END)) {
            if (parsed.dimensions().containsKey(dimension)) return dimension;
        }
        return null;
    }
}
