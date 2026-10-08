package com.kncatl.ohmyworld.expr;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.kncatl.ohmyworld.FormulaParser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * biome 行的原版群系查询（M3.3，1.3.2）：biome_at() 视图映射、vanilla 哨兵与无视图回退。
 */
class BiomeQueryExprTest {

    /** 可观察视图：记录六参并返回可断言的群系 id。 */
    private static final class RecordingQuery implements ExprEvaluator.BiomeQueryView {
        double t;
        double h;
        double c;
        double e;
        double d;
        double w;
        String result = "minecraft:plains";

        @Override
        public String biomeAt(double temperature, double humidity, double continentalness,
                              double erosion, double depth, double weirdness) {
            this.t = temperature;
            this.h = humidity;
            this.c = continentalness;
            this.e = erosion;
            this.d = depth;
            this.w = weirdness;
            return result;
        }
    }

    private static Object evalBiome(String source, int x, int z, int y, ExprEvaluator.BiomeQueryView view) {
        ExprNode node = ExprCompiler.compile(
                FormulaParser.expandLoops(new ExprParser(ExprLexer.tokenize(source)).parse()));
        ExprEvaluator.setBiomeQueryView(view);
        try {
            Map<String, Object> resolved = new HashMap<>();
            return ExprEvaluator.evalToBiome(node, x, z, 0, y,
                    id -> resolved.computeIfAbsent(id, key -> "resolved:" + key), null);
        } finally {
            ExprEvaluator.setBiomeQueryView(null);
        }
    }

    @Test
    void biomeAtReadsTheInjectedView() {
        RecordingQuery view = new RecordingQuery();
        Object result = evalBiome("biome_at(0.1, 0.2, 0.3, 0.4, 0.5, 0.6)", 0, 0, 0, view);
        // 返回值经 BIOME_RESOLVER 解析（与字面量同路径）
        assertEquals("resolved:minecraft:plains", result);
        assertEquals(0.1, view.t, 1e-9);
        assertEquals(0.2, view.h, 1e-9);
        assertEquals(0.3, view.c, 1e-9);
        assertEquals(0.4, view.e, 1e-9);
        assertEquals(0.5, view.d, 1e-9);
        assertEquals(0.6, view.w, 1e-9);
    }

    @Test
    void biomeAtReturnsNullWithoutViewOrTable() {
        assertNull(evalBiome("biome_at(0.1, 0.2, 0.3, 0.4, 0.5, 0.6)", 0, 0, 0, null));
        RecordingQuery empty = new RecordingQuery();
        empty.result = null;
        assertNull(evalBiome("biome_at(0.1, 0.2, 0.3, 0.4, 0.5, 0.6)", 0, 0, 0, empty));
    }

    @Test
    void vanillaBiomeValueCompilesToSentinel() {
        ExprNode node = ExprCompiler.compile(new ExprParser(ExprLexer.tokenize("vanilla")).parse());
        assertSame(ExprEvaluator.VANILLA, ExprEvaluator.eval(node, 0, 0, 0));
    }
}
