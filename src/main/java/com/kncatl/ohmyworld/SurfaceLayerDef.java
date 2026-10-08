package com.kncatl.ohmyworld;

import com.kncatl.ohmyworld.expr.ExprNode;

/**
 * 表面通道的一行：{@code surface y=a..b: <表达式>}（{@code surface: ...} 简写整维；
 * {@code surface[maxdepth=N]} 限制只对深度不超过 N 的方块求值）。
 *
 * <p>语义：区块地形铺完之后、写入区块之前，对实心方块自上而下应用表面规则；
 * 表达式返回方块或 {@code keep}（保持当前方块）。同一 y 上后写覆盖先写。
 * 变量 sd/sdb/wd/slope 的含义见指南与 {@code SurfacePass}。
 */
public class SurfaceLayerDef {
    private final int yStart, yEnd;
    private final boolean shorthand;
    private final int maxDepth;
    private final ExprNode expression;

    public SurfaceLayerDef(int yStart, int yEnd, boolean shorthand, int maxDepth, ExprNode expression) {
        this.yStart = yStart;
        this.yEnd = yEnd;
        this.shorthand = shorthand;
        this.maxDepth = maxDepth;
        this.expression = expression;
    }

    public int yStart() { return yStart; }

    public int yEnd() { return yEnd; }

    /** true 表示 {@code surface: <表达式>} 简写（整维；起止高度运行时解析）。 */
    public boolean shorthand() { return shorthand; }

    /** 只对 sd <= maxDepth 的方块求值（默认 8）。 */
    public int maxDepth() { return maxDepth; }

    public ExprNode expression() { return expression; }

    /** 运行期解析后的起点：简写或开区间（{@code y=..b}）都取维度最低 y。 */
    public int resolvedStart(int dimensionMinY) {
        return (shorthand || yStart == Integer.MIN_VALUE) ? dimensionMinY : yStart;
    }

    /** 运行期解析后的终点（含）：简写或开区间（{@code y=a..}）都取维度最高 y。 */
    public int resolvedEnd(int dimensionMaxY) {
        return (shorthand || yEnd == Integer.MAX_VALUE) ? dimensionMaxY : yEnd;
    }

    /** 本行是否覆盖给定高度（简写覆盖全部高度）。 */
    public boolean contains(int y) {
        return y >= yStart && y <= yEnd;
    }
}
