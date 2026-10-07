package com.kncatl.ohmyworld;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

import com.kncatl.ohmyworld.expr.ExprCompiler;
import com.kncatl.ohmyworld.expr.ExprEvaluator;
import com.kncatl.ohmyworld.expr.ExprLexer;
import com.kncatl.ohmyworld.expr.ExprNode;
import com.kncatl.ohmyworld.expr.ExprParser;

/**
 * 求值器数值原语的离线测试：只走数值路径，不触碰方块/群系注册表。
 */
class ExprEvaluatorTest {

    private static ExprNode parse(String expression) {
        return new ExprParser(ExprLexer.tokenize(expression)).parse();
    }

    private static double eval(String expression, int x, int z, int ly) {
        return ((Number) ExprEvaluator.eval(parse(expression), x, z, ly)).doubleValue();
    }

    private static double evalAt(String expression, int x, int z, int ly, int globalY) {
        return ((Number) ExprEvaluator.evalAt(parse(expression), x, z, ly, globalY)).doubleValue();
    }

    @Test
    void helpersFollowVanillaSemantics() {
        assertEquals(5.0, eval("clamp(7, 0, 5)", 0, 0, 0));
        assertEquals(0.0, eval("clamp(-2, 0, 5)", 0, 0, 0));
        assertEquals(25.0, eval("lerp(0, 100, 0.25)", 0, 0, 0));
        assertEquals(0.5, eval("smoothstep(0.5)", 0, 0, 0));
        assertEquals(1.0, eval("smoothstep(9)", 0, 0, 0));
        assertEquals(0.0, eval("smoothstep(-9)", 0, 0, 0));
        assertEquals(5.0, eval("map(1, 0, 2, 0, 10)", 0, 0, 0));
        assertEquals(0.0, eval("map(1, 2, 2, 0, 10)", 0, 0, 0));
    }

    @Test
    void absoluteYVariable() {
        assertEquals(123.0, evalAt("y", 7, 9, 4, 123));
        assertEquals(4.0, eval("y", 7, 9, 4)); // 无层上下文时 y = ly
        assertEquals(7.0, evalAt("{ let y = 7; y }", 0, 0, 0, 123)); // 层内 let 可以覆盖
    }

    @Test
    void spawnVariablesAreBuiltIn() {
        ExprEvaluator.setWorldSpawn(321, -654);
        assertEquals(321.0, eval("spawnx", 7, 9, 4));
        assertEquals(-654.0, eval("spawnz", 7, 9, 4));
        assertEquals(7.0 - 321.0, eval("x - spawnx", 7, 9, 4));
        assertEquals(-981.0, eval("spawnz * 1.5", 7, 9, 4));
    }

    @Test
    void noiseStaysInRangeAndIsDeterministic() {
        ExprEvaluator.setWorldSeed(12345L);
        for (int x = -40; x <= 40; x += 9) {
            for (int z = -40; z <= 40; z += 9) {
                for (int y = -30; y <= 30; y += 13) {
                    double n2 = eval("noise2(x, z, 32, 7)", x, z, 0);
                    double n3 = eval("noise3(x, y, z, 32, 7)", x, z, 0);
                    assertTrue(n2 >= -1 && n2 <= 1, "noise2 out of range: " + n2);
                    assertTrue(n3 >= -1 && n3 <= 1, "noise3 out of range: " + n3);
                }
            }
        }
        assertEquals(eval("noise2(3, 5, 32, 7)", 0, 0, 0), eval("noise2(3, 5, 32, 7)", 0, 0, 0));
        assertNotEquals(eval("noise2(3, 5, 32, 7)", 0, 0, 0), eval("noise2(3, 5, 32, 8)", 0, 0, 0));
        assertNotEquals(eval("noise2(3, 5, 32, 7)", 0, 0, 0), eval("noise2(3, 5, 64, 7)", 0, 0, 0));
    }

    @Test
    void fbmAndWorleyBehave() {
        ExprEvaluator.setWorldSeed(12345L);
        assertEquals(eval("noise2(11, 13, 64, 3)", 0, 0, 0), eval("fbm2(11, 13, 64, 1, 3)", 0, 0, 0));
        for (int x = -30; x <= 30; x += 11) {
            double fbm = eval("fbm2(x, 5, 96, 4, 2)", x, 5, 0);
            assertTrue(fbm >= -1 && fbm <= 1, "fbm2 out of range: " + fbm);
            double fbm3 = eval("fbm3(x, 5, 7, 96, 4, 2)", x, 7, 0);
            assertTrue(fbm3 >= -1 && fbm3 <= 1, "fbm3 out of range: " + fbm3);
            double worley = eval("worley2(x, 5, 40, 6)", x, 5, 0);
            assertTrue(worley >= 0 && worley <= 1, "worley2 out of range: " + worley);
            double worley3 = eval("worley3(x, 5, 7, 40, 6)", x, 7, 0);
            assertTrue(worley3 >= 0 && worley3 <= 1, "worley3 out of range: " + worley3);
        }
        double withSeed = eval("noise2(1, 2, 50, 1)", 0, 0, 0);
        ExprEvaluator.setWorldSeed(999L);
        assertNotEquals(withSeed, eval("noise2(1, 2, 50, 1)", 0, 0, 0));
    }

    @Test
    void compiledPathMatchesUncompiled() {
        ExprEvaluator.setWorldSeed(12345L);
        ExprEvaluator.setWorldSpawn(123, -456);
        String[] expressions = {
                "clamp(seed, 0, 0.5)", "lerp(seed, 5, 0.3)", "smoothstep(seed)",
                "map(seed, 0, 1, 10, 20)", "noise2(x, z, 40, 2)", "noise3(x, y, z, 40, 2)",
                "fbm2(x, z, 80, 3, 4)", "fbm3(x, y, z, 80, 3, 4)",
                "worley2(x, z, 40, 1)", "worley3(x, y, z, 40, 1)",
                "spawnx + spawnz", "(x - spawnx) * (x - spawnx) + (z - spawnz)",
                "{ let dx = x - spawnx; dx * dx < 400 ? seedhash(dx, spawnz, 3) : spawnx }",
        };
        int[][] points = {{0, 0, 0}, {3, 7, -4}, {-9, 2, 5}};
        for (String expression : expressions) {
            ExprNode plain = parse(expression);
            ExprNode compiled = ExprCompiler.compile(plain);
            for (int[] point : points) {
                double viaPlain = ((Number) ExprEvaluator.eval(plain, point[0], point[1], point[2])).doubleValue();
                double viaCompiled = ((Number) ExprEvaluator.eval(compiled, point[0], point[1], point[2])).doubleValue();
                assertEquals(viaPlain, viaCompiled, expression + " @" + Arrays.toString(point));
            }
        }
    }
}
