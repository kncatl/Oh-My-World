package com.kncatl.ohmyworld;

import com.kncatl.ohmyworld.expr.ExprNode;

/**
 * 公式群系的一层：{@code biome y=a..b: <表达式>}（{@code y=a} 单层亦可）。
 *
 * <p>{@code shorthand} 表示 {@code biome: <表达式>} 简写形式：它覆盖整个维度，
 * 实际起止高度在运行时按维度真实高度解析（此时 {@code ly = y - 维度最低 y}）；
 * 简写时 {@code yStart/yEnd} 为哨兵值，{@link #contains(int)} 仍可安全使用。
 *
 * <p>与方块层一致：同一 y 上后写的层覆盖先写的层；{@code expression} 是已编译的
 * 群系表达式（字面量为群系 id）。
 */
public class BiomeLayerDef {
    private final int yStart, yEnd;
    private final boolean shorthand;
    private final ExprNode expression;
    private final boolean columnInvariant;

    /**
     * @param expression      已编译的表达式（见 {@code ExprCompiler}）
     * @param columnInvariant 表达式是否与 ly 无关；需在编译前用
     *                        {@code ExprEvaluator.dependsOnLy} 判定后传入
     */
    public BiomeLayerDef(int yStart, int yEnd, boolean shorthand, ExprNode expression, boolean columnInvariant) {
        this.yStart = yStart;
        this.yEnd = yEnd;
        this.shorthand = shorthand;
        this.expression = expression;
        this.columnInvariant = columnInvariant;
    }

    public int yStart() { return yStart; }
    public int yEnd() { return yEnd; }

    /** true 表示 {@code biome: <表达式>} 简写（覆盖整维，起止高度运行时才解析）。 */
    public boolean shorthand() { return shorthand; }

    public ExprNode expression() { return expression; }

    /**
     * true 表示本层的结果在同一列内对所有 y 都相同（表达式不引用 ly），
     * 调用方可以只求值一次后整段高度复用。
     */
    public boolean columnInvariant() { return columnInvariant; }

    /** 本层是否覆盖给定高度（简写层覆盖全部高度）。 */
    public boolean contains(int y) {
        return y >= yStart && y <= yEnd;
    }
}
