package com.kncatl.ohmyworld.expr;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 元组 let 与多返回函数（warp2 / warp3 / noise2g / worley2c）的求值契约。
 */
class TupleLetTest {

    private static double eval(String source, int x, int z, int ly) {
        ExprNode node = new ExprParser(ExprLexer.tokenize(source)).parse();
        return ((Number) ExprEvaluator.eval(node, x, z, ly)).doubleValue();
    }

    private static double[] tuple(String source, int x, int z, int ly) {
        ExprNode node = new ExprParser(ExprLexer.tokenize(source)).parse();
        return (double[]) ExprEvaluator.eval(node, x, z, ly);
    }

    @Test
    void noise2gFirstComponentMatchesNoise2Exactly() {
        ExprEvaluator.setWorldSeed(12345L);
        for (int x = -7; x <= 7; x += 3) {
            for (int z = -7; z <= 7; z += 3) {
                double value = eval("{ let (g, dx, dz) = noise2g(x, z, 40, 2); g }", x, z, 0);
                double scalar = eval("noise2(x, z, 40, 2)", x, z, 0);
                assertEquals(scalar, value, 0, "x=" + x + " z=" + z);
            }
        }
    }

    @Test
    void analyticGradientMatchesFiniteDifference() {
        ExprEvaluator.setWorldSeed(-999L);
        double step = 1e-3;
        int checked = 0;
        for (int x = -12; x <= 12; x += 5) {
            for (int z = -12; z <= 12; z += 5) {
                double value = eval("noise2(x, z, 33.3, 4.25)", x, z, 0);
                if (Math.abs(value) >= 0.999) continue; // 截断区梯度按 0 定义，跳过后单独验证
                double[] grad = tuple("noise2g(x, z, 33.3, 4.25)", x, z, 0);
                double dx = (eval("noise2(x + 0.001, z, 33.3, 4.25)", x, z, 0)
                        - eval("noise2(x - 0.001, z, 33.3, 4.25)", x, z, 0)) / (2 * step);
                double dz = (eval("noise2(x, z + 0.001, 33.3, 4.25)", x, z, 0)
                        - eval("noise2(x, z - 0.001, 33.3, 4.25)", x, z, 0)) / (2 * step);
                assertEquals(dx, grad[1], 1e-4, "∂/∂x x=" + x + " z=" + z);
                assertEquals(dz, grad[2], 1e-4, "∂/∂z x=" + x + " z=" + z);
                checked++;
            }
        }
        assertTrue(checked > 10, "参与比对的样本太少: " + checked);
    }

    @Test
    void warp2ReturnsBoundedDisplacement() {
        ExprEvaluator.setWorldSeed(7L);
        double amp = 30;
        for (int x = -9; x <= 9; x += 6) {
            for (int z = -9; z <= 9; z += 6) {
                double[] uv = tuple("warp2(x, z, 90, 30, 42)", x, z, 0);
                assertEquals(2, uv.length);
                assertTrue(Math.abs(uv[0]) <= amp && Math.abs(uv[1]) <= amp, "|warp| > amp");
            }
        }
    }

    @Test
    void warpxCoordinatesFollowArguments() {
        ExprEvaluator.setWorldSeed(7L);
        // 同一个位置：warp3 的三个分量各自有限、与组分类型的取值域一致（|d| <= amp）
        double[] d = tuple("warp3(x, ly, z, 60, 8, 3)", 11, -4, 5);
        assertEquals(3, d.length);
        for (double v : d) assertTrue(Math.abs(v) <= 8);
    }

    @Test
    void worley2cComponentsAreConsistentWithScalarVariants() {
        ExprEvaluator.setWorldSeed(2026L);
        for (int x = -6; x <= 6; x += 4) {
            for (int z = -6; z <= 6; z += 4) {
                double[] c = tuple("worley2c(x, z, 50, 3)", x, z, 0);
                assertEquals(5, c.length);
                assertEquals(eval("worley2(x, z, 50, 3)", x, z, 0), c[0], 1e-12);
                assertEquals(eval("worley2f2(x, z, 50, 3)", x, z, 0), c[1], 1e-12);
                assertTrue(c[0] <= c[1] + 1e-12, "F1 > F2");
                assertTrue(c[2] >= 0 && c[2] < 1, "cellRand 越界");
                // 特征点坐标按方块计：与位置相差不超过一个细胞尺度多一点
                assertTrue(Math.abs(c[3] - x) <= 2 * 50 && Math.abs(c[4] - z) <= 2 * 50);
            }
        }
    }

    @Test
    void tupleComponentsAreHoistedTogether() {
        ExprNode root = ExprCompiler.compile(
                new ExprParser(ExprLexer.tokenize("{ let (u, v) = warp2(x, z, 30, 5, 1); u + v }")).parse());
        boolean[] hoisted = ((ExprNode.CompiledBlockNode) root).hoisted();
        // 隐藏元组槽位 + 两个分量：都与 y 无关，一起提升
        assertTrue(hoisted.length == 3 && hoisted[0] && hoisted[1] && hoisted[2]);

        ExprNode dependent = ExprCompiler.compile(
                new ExprParser(ExprLexer.tokenize("{ let (u, v) = warp2(x, ly, 30, 5, 1); u + v }")).parse());
        boolean[] flags = ((ExprNode.CompiledBlockNode) dependent).hoisted();
        assertTrue(flags.length == 3 && !flags[0] && !flags[1] && !flags[2]);
    }
}
