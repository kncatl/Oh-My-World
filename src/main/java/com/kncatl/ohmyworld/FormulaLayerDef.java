package com.kncatl.ohmyworld;

import net.minecraft.world.level.block.state.BlockState;

import com.kncatl.ohmyworld.expr.ExprEvaluator;
import com.kncatl.ohmyworld.expr.ExprNode;

public class FormulaLayerDef {
    private final int yStart, yEnd;
    private final ExprNode expression;
    private final boolean columnInvariant;

    /**
     * @param expression      已编译的表达式（见 {@code ExprCompiler}）
     * @param columnInvariant 表达式是否与 ly 无关；需在编译前用
     *                        {@code ExprEvaluator.dependsOnLy} 判定后传入
     */
    public FormulaLayerDef(int yStart, int yEnd, ExprNode expression, boolean columnInvariant) {
        this.yStart = yStart;
        this.yEnd = yEnd;
        this.expression = expression;
        this.columnInvariant = columnInvariant;
    }

    public int yStart() { return yStart; }
    public int yEnd() { return yEnd; }

    /**
     * true 表示本层的结果在同一列内对所有 y 都相同（表达式不引用 ly），
     * 调用方可以只求值一次后整段高度复用。
     */
    public boolean columnInvariant() { return columnInvariant; }

    public BlockState getBlock(int worldX, int worldZ, int globalY) {
        return ExprEvaluator.evalToBlock(expression, worldX, worldZ, globalY - yStart);
    }
}
