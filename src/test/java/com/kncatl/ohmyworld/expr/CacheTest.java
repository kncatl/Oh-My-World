package com.kncatl.ohmyworld.expr;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * cache2d / cache3d（1.3.1）：网格角点求值 + 双线性/三线性插值。
 *
 * <p>对线性表达式插值是精确的；非线性时角点处与直接求值一致。
 * 缓存只是优化——编译路径（带缓存）与未编译路径（直通）必须逐位一致。
 */
class CacheTest {

    private static double evalRaw(String source, int x, int z, int ly) {
        ExprNode node = new ExprParser(ExprLexer.tokenize(source)).parse();
        return ((Number) ExprEvaluator.eval(node, x, z, ly)).doubleValue();
    }

    private static double evalCompiled(String source, int x, int z, int ly) {
        ExprNode node = ExprCompiler.compile(new ExprParser(ExprLexer.tokenize(source)).parse());
        return ((Number) ExprEvaluator.eval(node, x, z, ly)).doubleValue();
    }

    @Test
    void linearExpressionsInterpolateExactly() {
        ExprEvaluator.setWorldSeed(4242L);
        for (int x = -20; x <= 20; x += 7) {
            for (int z = -20; z <= 20; z += 7) {
                assertEquals(x + z, evalCompiled("cache2d(x + z, 4)", x, z, 0), 1e-12);
                assertEquals(x * 2.0 + z * 3.0 + 7, evalCompiled("cache2d(x * 2 + z * 3 + 7, 8)", x, z, 0), 1e-12);
                assertEquals(x + 2.0 * z, evalCompiled("cache2d(x + 2 * z)", x, z, 0), 1e-12); // 默认 step=4
            }
        }
    }

    @Test
    void cache3dLinearExpressionsInterpolateExactly() {
        ExprEvaluator.setWorldSeed(4242L);
        ExprNode compiled = ExprCompiler.compile(
                new ExprParser(ExprLexer.tokenize("cache3d(x + 2 * z + 3 * y, 4, 8, 4)")).parse());
        for (int y = -16; y <= 64; y += 11) {
            for (int x = -16; x <= 16; x += 9) {
                for (int z = -16; z <= 16; z += 9) {
                    double expected = x + 2.0 * z + 3.0 * y;
                    double actual = ((Number) ExprEvaluator.evalAt(compiled, x, z, 0, y)).doubleValue();
                    assertEquals(expected, actual, 1e-12, "x=" + x + " y=" + y + " z=" + z);
                }
            }
        }
    }

    @Test
    void gridCornersMatchDirectEvaluationForNoise() {
        ExprEvaluator.setWorldSeed(777L);
        for (int x = -32; x <= 32; x += 16) {
            for (int z = -32; z <= 32; z += 16) {
                // 网格角点（step=8）：缓存值就是该点的表达式值
                double direct = evalRaw("noise2(x, z, 300, 5)", x, z, 0);
                double cached = evalCompiled("cache2d(noise2(x, z, 300, 5), 8)", x, z, 0);
                assertEquals(direct, cached, 1e-15, "corner x=" + x + " z=" + z);
            }
        }
    }

    @Test
    void compiledPathMatchesUncachedPathBitForBit() {
        ExprEvaluator.setWorldSeed(-123456789L);
        String[] sources = {
                "cache2d(noise2(x, z, 350, 9) + fbm2(x, z, 800, 3, 4), 8)",
                "cache2d(x * 2 + z, 8)",
                "cache3d(fbm2(x, z, 400, 3, 2) + y * 0.01, 4, 8, 4)",
                "cache3d(noise2(x, z, 250, 3), 16, 16, 16)",
        };
        for (String source : sources) {
            for (int x = -24; x <= 24; x += 9) {
                for (int z = -24; z <= 24; z += 9) {
                    for (int ly = 0; ly <= 32; ly += 16) {
                        double cached = evalCompiled(source, x, z, ly);
                        double uncached = evalRaw(source, x, z, ly);
                        assertEquals(uncached, cached, 0, source + " @ (" + x + "," + z + "," + ly + ")");
                    }
                }
            }
        }
    }

    @Test
    void valuesAreStableAcrossEvaluationOrderAndSeedTracks() {
        ExprEvaluator.setWorldSeed(31337L);
        ExprNode compiled = ExprCompiler.compile(
                new ExprParser(ExprLexer.tokenize("cache2d(noise2(x, z, 300, 5), 4)")).parse());
        double first = ((Number) ExprEvaluator.eval(compiled, 10, -6, 3)).doubleValue();
        // 穿插其它坐标的求值（触发缓存换列）
        for (int i = 0; i < 5; i++) ExprEvaluator.eval(compiled, 200 + i * 7, -30 + i, i);
        assertEquals(first, ((Number) ExprEvaluator.eval(compiled, 10, -6, 3)).doubleValue(), 0);

        // 换世界种子：值必须变化（缓存不能串种子）
        ExprEvaluator.setWorldSeed(31338L);
        assertNotEquals(first, ((Number) ExprEvaluator.eval(compiled, 10, -6, 3)).doubleValue());
    }

    @Test
    void compileProducesCacheNodesAndHoistingIsCorrect() {
        ExprNode root = ExprCompiler.compile(
                new ExprParser(ExprLexer.tokenize("{ let a = cache2d(noise2(x, z, 200, 3), 8); a }")).parse());
        ExprNode.CompiledBlockNode block = (ExprNode.CompiledBlockNode) root;
        assertInstanceOf(ExprNode.CompiledCache2dNode.class, block.values()[0]);
        assertTrue(block.hoisted()[0], "cache2d 与纵坐标无关，应可提升");

        ExprNode yRoot = ExprCompiler.compile(new ExprParser(ExprLexer.tokenize(
                "{ let a = cache3d(noise2(x, z, 200, 3) + y * 0.1, 4, 8, 4); a }")).parse());
        assertInstanceOf(ExprNode.CompiledCache3dNode.class,
                ((ExprNode.CompiledBlockNode) yRoot).values()[0]);
        assertTrue(!((ExprNode.CompiledBlockNode) yRoot).hoisted()[0], "cache3d 引用 y 时不得提升");
    }

    @Test
    void expressionsReferencingBindingsFallBackToUncachedPath() {
        // 引用元组分量的 cache2d 不会被编译成缓存节点（否则跨列复用会串值）——
        // 生产路径由校验直接拒绝，这里验证编译器的兜底不转换。
        ExprNode root = ExprCompiler.compile(new ExprParser(ExprLexer.tokenize(
                "{ let (u, v) = warp2(x, z, 200, 30, 6); cache2d(u + v, 8) }")).parse());
        ExprNode.CompiledBlockNode block = (ExprNode.CompiledBlockNode) root;
        assertTrue(block.body() instanceof ExprNode.FuncCallNode, "引用绑定的 cache2d 应回退为普通调用");
    }
}
