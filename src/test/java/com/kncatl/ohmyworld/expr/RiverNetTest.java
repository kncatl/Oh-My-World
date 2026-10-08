package com.kncatl.ohmyworld.expr;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * rivernet（1.3.1）的求值契约：分量值域、确定性、编译/缓存路径一致。
 * 河网几何（单调下降、汇流连续）由算法结构与冒烟验证；这里覆盖可离线断言的部分。
 */
class RiverNetTest {

    private static double[] evalTuple(String source, int x, int z) {
        ExprNode node = new ExprParser(ExprLexer.tokenize(source)).parse();
        return (double[]) ExprEvaluator.eval(node, x, z, 0);
    }

    private static double[] evalTupleCompiled(String source, int x, int z) {
        ExprNode node = ExprCompiler.compile(new ExprParser(ExprLexer.tokenize(source)).parse());
        return (double[]) ExprEvaluator.eval(node, x, z, 0);
    }

    @Test
    void componentsStayInSaneRanges() {
        ExprEvaluator.setWorldSeed(20261008L);
        for (int x = -600; x <= 600; x += 300) {
            for (int z = -600; z <= 600; z += 300) {
                double[] r = evalTuple("rivernet(192, 7)", x, z);
                assertEquals(4, r.length);
                assertTrue(r[0] >= 0, "dist < 0");
                assertTrue(r[1] >= 0, "width < 0");
                assertTrue(Double.isFinite(r[2]), "surf not finite");
                assertTrue(r[3] >= 0, "order < 0");
                // 有河段时半宽 >= w0=3；无河段（回退）时为 0
                assertTrue(r[1] == 0 || r[1] >= 3, "width in (0, 3): " + r[1]);
            }
        }
    }

    @Test
    void compiledCachePathMatchesUncachedPath() {
        ExprEvaluator.setWorldSeed(-424242L);
        for (int x = -500; x <= 500; x += 250) {
            for (int z = -500; z <= 500; z += 250) {
                double[] uncached = evalTuple("rivernet(192, 7)", x, z);
                double[] cached = evalTupleCompiled("rivernet(192, 7)", x, z);
                assertArrayEquals(uncached, cached, 0.0,
                        "缓存/直通不一致 @ (" + x + "," + z + ")");
            }
        }
    }

    @Test
    void deterministicAcrossRepeatedAndInterleavedEvaluation() {
        ExprEvaluator.setWorldSeed(777L);
        ExprNode compiled = ExprCompiler.compile(new ExprParser(ExprLexer.tokenize("rivernet(160, 3)")).parse());
        double[] first = (double[]) ExprEvaluator.eval(compiled, 123, -456, 0);
        for (int i = 0; i < 5; i++) {
            ExprEvaluator.eval(compiled, 1000 + i * 37, -1000 + i * 11, i);
        }
        double[] again = (double[]) ExprEvaluator.eval(compiled, 123, -456, 0);
        assertArrayEquals(first, again, 0.0);
    }

    @Test
    void customCoarseFieldChangesTheNetwork() {
        ExprEvaluator.setWorldSeed(99L);
        ExprNode custom = ExprCompiler.compile(new ExprParser(ExprLexer.tokenize(
                "rivernet(x * 0.01 + z * 0.005 + 55, 192, 7)")).parse());
        assertInstanceOf(ExprNode.CompiledRiverNetNode.class, custom);
        boolean differs = false;
        for (int x = -400; x <= 400 && !differs; x += 200) {
            for (int z = -400; z <= 400 && !differs; z += 200) {
                double[] a = (double[]) ExprEvaluator.eval(custom, x, z, 0);
                double[] b = evalTuple("rivernet(192, 7)", x, z);
                differs = a[0] != b[0] || a[2] != b[2];
            }
        }
        assertTrue(differs, "自定义 coarse 应改变河网");
    }

    @Test
    void worldSeedChangesTheNetwork() {
        ExprEvaluator.setWorldSeed(1L);
        double sumA = 0;
        for (int x = -400; x <= 400; x += 200) {
            sumA += evalTuple("rivernet(192, 7)", x, 0)[2];
        }
        ExprEvaluator.setWorldSeed(2L);
        double sumB = 0;
        for (int x = -400; x <= 400; x += 200) {
            sumB += evalTuple("rivernet(192, 7)", x, 0)[2];
        }
        assertNotEquals(sumA, sumB);
    }
}
