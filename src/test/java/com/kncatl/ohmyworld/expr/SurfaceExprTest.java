package com.kncatl.ohmyworld.expr;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 表面通道（1.3.1）的表达式级契约：surface 变量与 keep 哨兵。
 * 扫描逻辑本身在 {@code PatternData.applySurface}，由冒烟/服务端验证。
 */
class SurfaceExprTest {

    private static ExprNode compile(String source) {
        return ExprCompiler.compile(new ExprParser(ExprLexer.tokenize(source)).parse());
    }

    @Test
    void surfaceVariablesResolveToThreadLocalValues() {
        ExprNode node = compile("{ let a = sd * 10 + sdb + wd * 100 + slope * 1000; a }");
        ExprEvaluator.setSurfaceValues(1, 2, 3, 4);
        try {
            assertEquals(1 * 10 + 2 + 3 * 100 + 4 * 1000,
                    ((Number) ExprEvaluator.eval(node, 0, 0, 0)).doubleValue(), 1e-12);
        } finally {
            ExprEvaluator.clearSurfaceValues();
        }
        // 清除后回退 0（非表面上下文不会读到残值）
        assertEquals(0, ((Number) ExprEvaluator.eval(node, 0, 0, 0)).doubleValue(), 1e-12);
    }

    @Test
    void keepEvaluatesToTheSentinel() {
        ExprNode node = compile("keep");
        assertSame(ExprEvaluator.SURFACE_KEEP, ExprEvaluator.evalToSurface(node, 5, 6, 7, 8));

        ExprNode ternary = compile("x > 0 ? keep : keep");
        assertSame(ExprEvaluator.SURFACE_KEEP, ExprEvaluator.evalToSurface(ternary, 1, 0, 0, 0));
    }

    @Test
    void surfaceVariablesAreVerticalDependentAndNotHoisted() {
        ExprNode raw = new ExprParser(ExprLexer.tokenize("{ let a = sd; a }")).parse();
        assertTrue(ExprEvaluator.dependsOnLy(raw), "sd 按方块位置变化，必须视为与纵坐标相关");

        ExprNode.CompiledBlockNode block =
                (ExprNode.CompiledBlockNode) compile("{ let a = sd; a }");
        assertTrue(!block.hoisted()[0], "sd 绑定不得提升");

        // keep 不随纵坐标变化（常量值），可以提升
        ExprNode rawKeep = new ExprParser(ExprLexer.tokenize("{ let a = keep; a }")).parse();
        assertTrue(!ExprEvaluator.dependsOnLy(rawKeep));
    }
}
