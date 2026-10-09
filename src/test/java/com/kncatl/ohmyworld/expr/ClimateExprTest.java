package com.kncatl.ohmyworld.expr;

import org.junit.jupiter.api.Test;

import com.kncatl.ohmyworld.FormulaParser;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 共享气候（M3，1.3.2）：climate() 字段码/坐标映射、无视图回退、peaks 折叠，
 * 以及编译/未编译路径一致（视图由测试注入）。
 */
class ClimateExprTest {

    private static double eval(String source, int x, int z, int y) {
        ExprNode node = ExprCompiler.compile(new ExprParser(ExprLexer.tokenize(source)).parse());
        return ((Number) ExprEvaluator.evalAt(node, x, z, 0, y)).doubleValue();
    }

    private static double evalExpanded(String source, int x, int z, int y) {
        ExprNode node = ExprCompiler.compile(
                FormulaParser.expandLoops(new ExprParser(ExprLexer.tokenize(source)).parse()));
        return ((Number) ExprEvaluator.evalAt(node, x, z, 0, y)).doubleValue();
    }

    private static void withView(ExprEvaluator.VanillaView view, Runnable body) {
        ExprEvaluator.setVanillaView(view);
        try {
            body.run();
        } finally {
            ExprEvaluator.setVanillaView(null);
        }
    }

    @Test
    void climateReadsTheInjectedView() {
        withView((code, x, y, z) -> code * 1000 + y * 10 + x * 0.1 + z * 0.001, () -> {
            // 2D 形式在 y=63 采样
            assertEquals(0 * 1000 + 63 * 10 + 10 * 0.1 + 20 * 0.001,
                    eval("climate(temperature, 10, 20)", 0, 0, 0), 1e-9);
            assertEquals(1 * 1000 + 63 * 10 + 10 * 0.1 + 20 * 0.001,
                    eval("climate(humidity, 10, 20)", 0, 0, 0), 1e-9);
            // 3D 形式传入 y
            assertEquals(5 * 1000 + 100 * 10 + 10 * 0.1 + 20 * 0.001,
                    eval("climate(depth, 10, 100, 20)", 0, 0, 0), 1e-9);
            // 坐标可以是表达式；字段码对应表由编译器兜底解析
            assertEquals(4 * 1000 + 63 * 10 + 3 * 0.1,
                    eval("climate(weirdness, x + 3, z)", 0, 0, 0), 1e-9);
        });
    }

    @Test
    void climateReturnsZeroWithoutAView() {
        assertEquals(0, eval("climate(temperature, 10, 20)", 0, 0, 0), 1e-12);
        assertEquals(0, eval("climate(depth, 10, 100, 20)", 0, 0, 0), 1e-12);
    }

    @Test
    void peaksFoldsLikeVanilla() {
        assertEquals(-1, eval("peaks(0)", 0, 0, 0), 1e-12);
        assertEquals(1, eval("peaks(0.6666666666666666)", 0, 0, 0), 1e-12);
        assertEquals(1, eval("peaks(-0.6666666666666666)", 0, 0, 0), 1e-12);
        assertEquals(0, eval("peaks(1)", 0, 0, 0), 1e-12);
        assertEquals(0, eval("peaks(-1)", 0, 0, 0), 1e-12);
        assertEquals(0.5, eval("peaks(0.5)", 0, 0, 0), 1e-12);
    }

    @Test
    void compiledMatchesUncompiledUnderAView() {
        withView((code, x, y, z) -> code + x * 0.001 + y * 0.01 + z * 0.0001, () -> {
            ExprNode raw = FormulaParser.expandLoops(new ExprParser(ExprLexer.tokenize(
                    "{ let t = climate(temperature, x, z) * 2 + climate(weirdness, x, 100, z); t }")).parse());
            ExprNode compiled = ExprCompiler.compile(raw);
            for (int x = -3; x <= 3; x += 3) {
                for (int z = -3; z <= 3; z += 3) {
                    Object a = ExprEvaluator.eval(raw, x, z, 0);
                    Object b = ExprEvaluator.eval(compiled, x, z, 0);
                    assertEquals(((Number) a).doubleValue(), ((Number) b).doubleValue(), 1e-12);
                }
            }
        });
    }

    /**
     * QUART（4×4×4 格）按格缓存：只经气候等 4×4×4 量化源依赖 y 的绑定在同一格内
     * 只求值一次，换格后重新求值；结果与逐格重算一致。
     */
    @Test
    void cellBindingsAreCachedPerQuartCell() {
        int[] calls = {0};
        // 与 BiomeControl 的实现一致：采样按 x>>2 / y>>2 / z>>2 量化
        withView((code, x, y, z) -> {
            calls[0]++;
            return (x >> 2) * 3 + (y >> 2) * 5 + (z >> 2) * 7;
        }, () -> {
            ExprNode compiled = ExprCompiler.compile(new ExprParser(ExprLexer.tokenize(
                    "{ let t = climate(temperature, x, y, z); t > 0 ? t : t + 1 }")).parse());
            // 同一 4×4×4 格内遍历 8 个点：绑定只求值一次
            for (int x = 4; x < 6; x++) {
                for (int z = 4; z < 6; z++) {
                    for (int y = 8; y < 10; y++) {
                        ExprEvaluator.evalAt(compiled, x, z, y, y);
                    }
                }
            }
            assertEquals(1, calls[0], "同一 4×4×4 格内 climate 绑定应只求值一次");
            // 换到下一个格：重新求值
            ExprEvaluator.evalAt(compiled, 8, 8, 16, 16);
            assertEquals(2, calls[0], "换格后应重新求值");
        });
    }

    /**
     * 变换过的坐标实参不得按格缓存：{@code climate(t, x + 3, y, z)} 的采样点
     * 在 4×4×4 格内可能跨格变化，编译后的求值必须与逐次直算一致
     * （分类收紧的回归；若误判为格内不变，单元测试会当场抓到）。
     */
    @Test
    void transformedCoordinatesAreNotCachedPerCell() {
        withView((code, x, y, z) -> (x >> 2) + (y >> 2) * 31 + (z >> 2) * 101, () -> {
            ExprNode raw = new ExprParser(ExprLexer.tokenize(
                    "{ let t = climate(temperature, x + 3, y, z); t }")).parse();
            ExprNode compiled = ExprCompiler.compile(raw);
            for (int x = 0; x < 8; x++) {
                for (int y = 8; y < 12; y++) {
                    double expected = ((Number) ExprEvaluator.evalAt(raw, x, 5, y, y)).doubleValue();
                    double actual = ((Number) ExprEvaluator.evalAt(compiled, x, 5, y, y)).doubleValue();
                    assertEquals(expected, actual, 1e-12,
                            "x=" + x + " y=" + y + " 处结果不一致");
                }
            }
        });
    }
}
