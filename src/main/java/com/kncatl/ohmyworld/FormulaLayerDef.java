package com.kncatl.ohmyworld;

import net.minecraft.world.level.block.state.BlockState;

import com.kncatl.ohmyworld.expr.ExprEvaluator;
import com.kncatl.ohmyworld.expr.ExprNode;

public class FormulaLayerDef {
    private final int yStart, yEnd;
    private final ExprNode expression;
    private final boolean columnInvariant;
    /** 叠加窗口（y=surf(a..b)）；非 null 时 yStart/yEnd 是相对 sy 的偏移（预览按 sy=0 近似）。 */
    private final Integer windowStart, windowEnd;

    /**
     * @param expression      已编译的表达式（见 {@code ExprCompiler}）
     * @param columnInvariant 表达式是否与 ly 无关；需在编译前用
     *                        {@code ExprEvaluator.dependsOnLy} 判定后传入
     */
    public FormulaLayerDef(int yStart, int yEnd, ExprNode expression, boolean columnInvariant) {
        this(yStart, yEnd, expression, columnInvariant, null, null);
    }

    /** 带叠加窗口的构造：windowStart/windowEnd 是相对 sy 的偏移（含）。 */
    public FormulaLayerDef(int yStart, int yEnd, ExprNode expression, boolean columnInvariant,
                           Integer windowStart, Integer windowEnd) {
        this.yStart = yStart;
        this.yEnd = yEnd;
        this.expression = expression;
        this.columnInvariant = columnInvariant;
        this.windowStart = windowStart;
        this.windowEnd = windowEnd;
    }

    public int yStart() { return yStart; }
    public int yEnd() { return yEnd; }

    /** 是否是叠加窗口层（y=surf(a..b)）：实际范围按每列的 sy 计算。 */
    public boolean hasWindow() { return windowStart != null; }

    /** 窗口下界偏移（相对 sy；仅窗口层有效）。 */
    public int windowStart() { return windowStart; }

    /** 窗口上界偏移（相对 sy；仅窗口层有效）。 */
    public int windowEnd() { return windowEnd; }

    /** 已编译的层表达式（供遍历/统计使用）。 */
    public ExprNode expression() { return expression; }

    /**
     * 运行期解析后的层起点：{@code y=..b}（开区间）取维度最低 y，
     * 此时 {@code ly = y - 维度最低 y}；显式起点则原样返回。
     */
    public int resolvedStart(int dimensionMinY) {
        return yStart == Integer.MIN_VALUE ? dimensionMinY : yStart;
    }

    /**
     * true 表示本层的结果在同一列内对所有 y 都相同（表达式不引用 ly），
     * 调用方可以只求值一次后整段高度复用。
     */
    public boolean columnInvariant() { return columnInvariant; }

    public BlockState getBlock(int worldX, int worldZ, int globalY, int dimensionMinY) {
        int start = resolvedStart(dimensionMinY);
        return ExprEvaluator.evalToBlock(expression, worldX, worldZ, globalY - start, globalY);
    }

    /**
     * 原样求值（叠加模式用）：返回 {@code BlockState}、{@link ExprEvaluator#VANILLA}
     * 或 {@link ExprEvaluator#SURFACE_KEEP} 等哨兵，由调用方解释。
     */
    public Object evalResult(int worldX, int worldZ, int globalY, int dimensionMinY) {
        int start = resolvedStart(dimensionMinY);
        return ExprEvaluator.evalAt(expression, worldX, worldZ, globalY - start, globalY);
    }
}
