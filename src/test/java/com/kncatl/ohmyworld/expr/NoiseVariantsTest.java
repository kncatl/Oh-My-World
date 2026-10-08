package com.kncatl.ohmyworld.expr;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 1.3.0 新噪声变体的值域 / 参数夹取与数学助手的性质
 * （黄金样本见 {@link FreezeContractTest}）。
 */
class NoiseVariantsTest {

    private static double eval(String source, int x, int z, int ly) {
        ExprNode node = new ExprParser(ExprLexer.tokenize(source)).parse();
        return ((Number) ExprEvaluator.eval(node, x, z, ly)).doubleValue();
    }

    @Test
    void multiOctaveVariantsStayInRange() {
        ExprEvaluator.setWorldSeed(31337L);
        for (int x = -40; x <= 40; x += 13) {
            for (int z = -40; z <= 40; z += 13) {
                assertInRange(eval("fbma2(x, z, 500, 3, 1, 0.5, 0.25, -0.125)", x, z, 0), -1, 1);
                assertInRange(eval("fbm2(x, z, 300, 4, 7, 2.2, 0.55)", x, z, 0), -1, 1);
                assertInRange(eval("fbm2e(x, z, 600, 4, 3, 3)", x, z, 0), -1, 1);
                assertInRange(eval("ridged2(x, z, 400, 4, 5, 2)", x, z, 0), 0, 1);
                assertInRange(eval("billow2(x, z, 400, 4, 5, 2)", x, z, 0), 0, 1);
            }
        }
    }

    @Test
    void octavesAndParametersAreClamped() {
        ExprEvaluator.setWorldSeed(9L);
        assertEquals(eval("fbm2(x, z, 300, 99, 7)", 5, 9, 0), eval("fbm2(x, z, 300, 8, 7)", 5, 9, 0), 0);
        assertEquals(eval("fbm2(x, z, 300, 0, 7)", 5, 9, 0), eval("fbm2(x, z, 300, 1, 7)", 5, 9, 0), 0);
        assertEquals(eval("ridged2(x, z, 300, 4, 5, 99)", 5, 9, 0),
                eval("ridged2(x, z, 300, 4, 5, 8)", 5, 9, 0), 0);
    }

    @Test
    void sharpnessChangesRidgeShape() {
        ExprEvaluator.setWorldSeed(11L);
        assertNotEquals(eval("ridged2(x, z, 400, 3, 2, 1)", 17, 29, 0),
                eval("ridged2(x, z, 400, 3, 2, 4)", 17, 29, 0));
    }

    @Test
    void terraceProducesStepsAndIsMonotonic() {
        assertEquals(-1.0, eval("terrace(-1, 4, 16)", 0, 0, 0), 1e-9);
        assertEquals(0.0, eval("terrace(0, 4, 16)", 0, 0, 0), 1e-9);
        assertEquals(0.5, eval("terrace(0.5, 4, 16)", 0, 0, 0), 1e-9);

        double prev = -1e9;
        for (int i = 0; i <= 100; i++) {
            double v = eval("terrace(x * 0.01, 6, 8)", i, 0, 0);
            assertTrue(v >= prev - 1e-12, "terrace 非单调 @ " + i);
            prev = v;
        }
    }

    @Test
    void biasAndGainAreIdentityAtHalf() {
        // b=0.5 / g=0.5 时二者都约等于恒等
        for (int i = 0; i <= 10; i++) {
            double t = i / 10.0;
            int px = i * 100; // x * 0.001 = t
            assertEquals(t, eval("bias(x * 0.001, 0.5)", px, 0, 0), 1e-12);
            assertEquals(t, eval("gain(x * 0.001, 0.5)", px, 0, 0), 1e-12);
        }
        assertEquals(0.0, eval("gain(0, 0.3)", 0, 0, 0), 1e-12);
        assertEquals(1.0, eval("gain(1, 0.7)", 0, 0, 0), 1e-12);
        // Schlick bias 的定义性质：bias(0.5, b) = b（把中点推向 b）
        assertEquals(0.3, eval("bias(0.5, 0.3)", 0, 0, 0), 1e-9);
        // gain 关于 0.5 对称：gain(0.5, g) = 0.5
        assertEquals(0.5, eval("gain(0.5, 0.3)", 0, 0, 0), 1e-12);
    }

    @Test
    void noiseRespondsToWorldSeed() {
        ExprEvaluator.setWorldSeed(1L);
        double a = eval("fbm2(x, z, 300, 4, 1)", 123, 456, 0);
        ExprEvaluator.setWorldSeed(2L);
        double b = eval("fbm2(x, z, 300, 4, 1)", 123, 456, 0);
        assertNotEquals(a, b);
    }

    private static void assertInRange(double v, double lo, double hi) {
        assertTrue(v >= lo - 1e-12 && v <= hi + 1e-12, "越界: " + v);
    }
}
