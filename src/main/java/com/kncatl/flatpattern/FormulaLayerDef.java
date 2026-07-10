package com.kncatl.flatpattern;

import net.minecraft.world.level.block.state.BlockState;

import com.kncatl.flatpattern.expr.ExprEvaluator;
import com.kncatl.flatpattern.expr.ExprNode;

public class FormulaLayerDef {
    private final int yStart, yEnd;
    private final ExprNode expression;

    public FormulaLayerDef(int yStart, int yEnd, ExprNode expression) {
        this.yStart = yStart;
        this.yEnd = yEnd;
        this.expression = expression;
    }

    public int yStart() { return yStart; }
    public int yEnd() { return yEnd; }

    public BlockState getBlock(int worldX, int worldZ, int globalY) {
        return ExprEvaluator.evalToBlock(expression, worldX, worldZ, globalY - yStart);
    }
}
