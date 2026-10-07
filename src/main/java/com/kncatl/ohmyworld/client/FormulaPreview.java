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
 * 采样区域为以 ({@code centerX},{@code centerZ}) 为中心的 {@link #SIZE}×{@link #SIZE} 个采样点，
 * 相邻采样点相距 {@code spacing} 个方块（间距 1 = 逐方块；间距 2/4/8… = 缩小视野看大格局）。
 */
public final class FormulaPreview {

    /** 采样网格边长（缩放 1 倍时的列数）。 */
    public static final int SIZE = 36;

    /** 空列（全空气）的显示色。 */
    private static final int EMPTY_COLOR = 0xFF141414;

    /** 给定缩放时的采样网格边长：缩放 >1 用双倍密度（像素减半，提升显示精度）。 */
    public static int cellsFor(int spacing) {
        return spacing > 1 ? SIZE * 2 : SIZE;
    }

    public record Result(int[] colors, String dimensionKey, int cells) {}

    private FormulaPreview() {}

    /** 计算指定维度、指定视野的预览；该维度不在公式里时返回 null。 */
    public static Result compute(FormulaParser.DimensionParseResult parsed, String dimension,
                                 int centerX, int centerZ, int spacing) {
        if (dimension == null) return null;
        FormulaParser.ParsedDimension parsedDim = parsed.dimensions().get(dimension);
        if (parsedDim == null || parsedDim.layers().isEmpty()) return null;

        PatternData.PatternSnapshot snapshot = new PatternData.PatternSnapshot(
                List.copyOf(parsedDim.layers()), "", 0L, DimensionRules.StructureRule.ALL, null, false,
                List.of(), DimensionRules.BiomeFallback.NONE);
        // 与各维度实际高度保持一致（超世界 -64 起、384 高；下界/末地 0 起、256 高）
        int minY = FormulaParser.DIM_OVERWORLD.equals(dimension) ? -64 : 0;
        int total = FormulaParser.DIM_OVERWORLD.equals(dimension) ? 384 : 256;
        int step = Math.max(1, spacing);
        int cells = cellsFor(step);
        int origin = -cells / 2;

        int[] colors = new int[cells * cells];
        for (int dz = 0; dz < cells; dz++) {
            for (int dx = 0; dx < cells; dx++) {
                int worldX = centerX + (origin + dx) * step;
                int worldZ = centerZ + (origin + dz) * step;
                BlockState[] column = PatternData.buildColumn(snapshot, worldX, worldZ, minY, total);
                colors[dz * cells + dx] = topColor(column);
            }
        }
        return new Result(colors, dimension, cells);
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
