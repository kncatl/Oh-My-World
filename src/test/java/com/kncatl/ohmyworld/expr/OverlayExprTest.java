package com.kncatl.ohmyworld.expr;

import org.junit.jupiter.api.Test;

import com.kncatl.ohmyworld.FormulaParser;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 叠加模式（M3.5，1.3.2）：sy/sw 列量与 vsolid/vfluid/vair 快照谓词的视图映射与无视图回退。
 * （vis 的方块参数需要方块注册表，单测环境不可用——由真服冒烟覆盖。）
 */
class OverlayExprTest {

    /** 可观察视图：列量/谓词按坐标给出确定值。 */
    private static final class FakeOverlay implements ExprEvaluator.OverlayView {
        @Override
        public boolean vanillaIs(int x, int y, int z, net.minecraft.world.level.block.state.BlockState target) {
            return false;
        }

        @Override
        public boolean vanillaSolid(int x, int y, int z) {
            return y < 0;
        }

        @Override
        public boolean vanillaFluid(int x, int y, int z) {
            return y == 5;
        }

        @Override
        public boolean vanillaAir(int x, int y, int z) {
            return y > 100;
        }

        @Override
        public int sy(int x, int z) {
            return 63 + x;
        }

        @Override
        public int sw(int x, int z) {
            return 40 + z;
        }
    }

    private static double eval(String source, int x, int z, int y) {
        ExprNode node = ExprCompiler.compile(
                FormulaParser.expandLoops(new ExprParser(ExprLexer.tokenize(source)).parse()));
        return ((Number) ExprEvaluator.evalAt(node, x, z, y, y)).doubleValue();
    }

    @Test
    void columnValuesReadTheInjectedView() {
        ExprEvaluator.setOverlayView(new FakeOverlay());
        try {
            assertEquals(63 + 3, eval("sy", 3, -2, 10), 1e-9);
            assertEquals(40 + (-2), eval("sw", 3, -2, 10), 1e-9);
            // sy/sw 是列量：同一列不同 y 值相同
            assertEquals(eval("sy", 3, -2, 10), eval("sy", 3, -2, 70), 1e-9);
            // 组合使用：sw < sy ? ...
            assertEquals(1, eval("sw < sy ? 1 : 0", 0, 0, 0), 1e-9);
            assertEquals(0, eval("sw < sy ? 1 : 0", 0, 30, 0), 1e-9);
        } finally {
            ExprEvaluator.setOverlayView(null);
        }
    }

    @Test
    void predicatesReadTheInjectedView() {
        ExprEvaluator.setOverlayView(new FakeOverlay());
        try {
            assertEquals(1, eval("vsolid", 0, 0, -10), 1e-9);
            assertEquals(0, eval("vsolid", 0, 0, 10), 1e-9);
            assertEquals(1, eval("vfluid", 0, 0, 5), 1e-9);
            assertEquals(0, eval("vfluid", 0, 0, 6), 1e-9);
            assertEquals(1, eval("vair", 0, 0, 200), 1e-9);
            assertEquals(0, eval("vair", 0, 0, 0), 1e-9);
            // 谓词可在条件里组合
            assertEquals(1, eval("vsolid && !vair ? 1 : 0", 0, 0, -1), 1e-9);
        } finally {
            ExprEvaluator.setOverlayView(null);
        }
    }

    @Test
    void returnsZeroWithoutAView() {
        assertEquals(0, eval("sy", 3, -2, 10), 1e-12);
        assertEquals(0, eval("sw", 3, -2, 10), 1e-12);
        assertEquals(0, eval("vsolid", 0, 0, -10), 1e-12);
        assertEquals(0, eval("vfluid", 0, 0, 5), 1e-12);
        assertEquals(0, eval("vair", 0, 0, 200), 1e-12);
    }
}
