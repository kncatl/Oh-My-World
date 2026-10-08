package com.kncatl.ohmyworld.expr;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 1.3.0 新函数的冻结契约（黄金样本）。
 *
 * <p>每个函数在固定世界种子下、1024 个由哈希确定的 (x, z, ly) 点上求值，
 * 值量化到 1e-6 后聚合为 64 位哈希并与常量比对。任何算法改动（同种子下结果变化）
 * 都会让本测试失败——发布后<strong>不得</strong>修改实现来让测试通过，
 * 而应新增函数名（见 Noise 与 ExprEvaluator 的冻结契约说明）。
 *
 * <p>{@code fbm2e(k=0)} 与 {@code fbm2} 的一致性属实现约定，单列断言。
 */
class FreezeContractTest {

    private static long sampleHash(String source, long seed) {
        ExprNode node = ExprCompiler.compile(new ExprParser(ExprLexer.tokenize(source)).parse());
        long h = 0x9E3779B97F4A7C15L ^ seed;
        for (int i = 0; i < 1024; i++) {
            h = Noise.mix64(h + i);
            long h2 = Noise.mix64(h + 0xBF58476D1CE4E5B9L);
            int x = (int) ((h >>> 20) % 4001) - 2000;
            int z = (int) ((h2 >>> 20) % 4001) - 2000;
            int ly = (int) ((h2 >>> 40) % 7) - 3;
            Object v = ExprEvaluator.eval(node, x, z, ly);
            double quantized = Math.round(((Number) v).doubleValue() * 1e6) / 1e6;
            h = Noise.mix64(h + Double.doubleToRawLongBits(quantized));
        }
        return h;
    }

    private static void check(String name, String source, long expected) {
        ExprEvaluator.setWorldSeed(-4815162342L);
        assertEquals(expected, sampleHash(source, 0x51ED270BL), "冻结样本漂移: " + name);
    }

    @Test
    void noiseVariants() {
        check("fbm2_7", "fbm2(x, z, 300, 4, 7, 2.2, 0.55)", 6511820838464762238L);
        check("fbma2", "fbma2(x, z, 500, 3, 1, 0.5, 0.25, 0.125)", -5981019544520796822L);
        check("ridged2", "ridged2(x, z, 400, 4, 5, 2)", 284446884860913642L);
        check("billow2", "billow2(x, z, 400, 4, 5, 2)", -7947630661798675399L);
        check("fbm2e", "fbm2e(x, z, 600, 4, 3, 3)", -8368413375023061806L);
    }

    @Test
    void tupleFunctions() {
        check("warp2", "{ let (u, v) = warp2(x, z, 300, 40, 9); u * 0.01 + v }",
                -8354666684940200037L);
        check("warp3", "{ let (u, v, w) = warp3(x, ly, z, 300, 40, 9); u + v * 0.1 + w * 0.01 }",
                -5006715757986160636L);
        check("noise2g", "{ let (v, gx, gz) = noise2g(x, z, 250, 11); v + gx * 10 + gz * 100 }",
                3544562783787256938L);
        check("worley2c", "{ let (f1, f2, c, px, pz) = worley2c(x, z, 350, 13);"
                        + " f1 + f2 * 2 + c * 3 + px * 0.001 + pz * 0.002 }",
                5066221262406872594L);
    }

    @Test
    void mathHelpers() {
        check("terrace", "terrace(x * 0.001 + z * 0.002, 8, 3)", -5875428359595855577L);
        check("schlick", "bias(fract(x * 0.01), 0.3) + gain(fract(z * 0.01), 0.7)",
                7523331586240329185L);
        check("helpers", "smootherstep(fract(x * 0.005)) + tanh(x * 0.01) + hypot(x, z) * 0.001"
                        + " + saturate(x * 0.0001) + atan2(x, z + 1) + step(0, x) + fract(z * 0.003)",
                4641663925162050630L);
        check("select", "select(x > z, x * 0.01, z * 0.02)", 5402620494418938307L);
        check("variadic", "min(x, z, 0.5) + max(x, z, -0.5) * 0.01", -4399101345933879131L);
    }

    @Test
    void spatialHelpers() {
        check("slope", "slope(noise2(x, z, 400, 21) + z * 0.001)", 7026685356270493744L);
        check("grad", "{ let (gx, gz) = grad(noise2(x, z, 400, 21)); gx + gz * 2 }",
                8222446108824118690L);
        check("curv", "curv(noise2(x, z, 400, 21))", -2792307359770627640L);
        check("isodist", "isodist(noise2(x, z, 500, 23) - 0.2)", -4285367076829557041L);
    }

    @Test
    void fbm2eWithZeroDampingEqualsFbm2() {
        ExprEvaluator.setWorldSeed(777L);
        for (int x = -13; x <= 13; x += 7) {
            for (int z = -13; z <= 13; z += 7) {
                double a = ((Number) ExprEvaluator.eval(
                        new ExprParser(ExprLexer.tokenize("fbm2e(x, z, 320, 4, 6, 0)")).parse(), x, z, 0)).doubleValue();
                double b = ((Number) ExprEvaluator.eval(
                        new ExprParser(ExprLexer.tokenize("fbm2(x, z, 320, 4, 6)")).parse(), x, z, 0)).doubleValue();
                assertEquals(b, a, 0, "x=" + x + " z=" + z);
            }
        }
    }
}
