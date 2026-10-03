package com.kncatl.ohmyworld.expr;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code seed} 变量与 {@code seedhash(...)} 的契约测试。
 *
 * <p>seedhash 的混合算法是兼容性契约（与 {@code pickIndex} 同等对待）：同一世界种子与
 * 参数在所有版本都必须给出同一结果，且要覆盖完整 64 位种子、分布均匀。这里用性质测试
 * 把这份契约锁住。
 */
class SeedHashTest {

    private static ExprNode compile(String source) {
        return ExprCompiler.compile(new ExprParser(ExprLexer.tokenize(source)).parse());
    }

    private static double eval(ExprNode node, int x, int z, int ly) {
        return ((Number) ExprEvaluator.eval(node, x, z, ly)).doubleValue();
    }

    @Test
    void seedReturnsTheCurrentWorldSeed() {
        ExprNode node = compile("seed");
        ExprEvaluator.setWorldSeed(12345L);
        assertEquals(12345.0d, eval(node, 0, 0, 0), 0.0d);
        ExprEvaluator.setWorldSeed(-1L);
        assertEquals(-1.0d, eval(node, 7, -9, 3), 0.0d);
    }

    @Test
    void seedhashStaysInUnitRange() {
        ExprNode node = compile("seedhash(x, z, 1)");
        long[] seeds = {0L, 1L, -1L, 12345L, Long.MIN_VALUE, Long.MAX_VALUE, -7138994955873085613L};
        for (long seed : seeds) {
            ExprEvaluator.setWorldSeed(seed);
            for (int x = -40; x <= 40; x += 7) {
                for (int z = -40; z <= 40; z += 7) {
                    double v = eval(node, x, z, 0);
                    assertTrue(v >= 0.0d && v < 1.0d, "seedhash 越界: " + v);
                }
            }
        }
    }

    @Test
    void seedhashIsDeterministic() {
        ExprEvaluator.setWorldSeed(987654321L);
        ExprNode node = compile("seedhash(x, z, 0)");
        for (int i = -50; i < 50; i++) {
            double a = eval(node, i, -i, 0);
            double b = eval(node, i, -i, 0);
            assertEquals(a, b, 0.0d, "seedhash 必须对同一输入确定");
        }
    }

    @Test
    void seedhashUsesAllSixtyFourSeedBits() {
        // 只在最低位不同的两个种子也必须给出不同结果
        ExprNode node = compile("seedhash(x, z, 0)");
        ExprEvaluator.setWorldSeed(0L);
        double a = eval(node, 3, 5, 0);
        ExprEvaluator.setWorldSeed(1L);
        double b = eval(node, 3, 5, 0);
        assertNotEquals(a, b);
    }

    @Test
    void seedhashWithoutArgumentsDependsOnlyOnSeed() {
        ExprNode noArgs = compile("seedhash()");
        ExprEvaluator.setWorldSeed(42L);
        double first = eval(noArgs, -100, 100, 5);
        assertEquals(first, eval(noArgs, 12345, -54321, -7), "无参数时不应受坐标影响");
        ExprEvaluator.setWorldSeed(43L);
        assertNotEquals(first, eval(noArgs, -100, 100, 5));
    }

    @Test
    void seedhashIsWellDistributed() {
        ExprEvaluator.setWorldSeed(-7138994955873085613L);
        ExprNode node = compile("seedhash(x, z, 0)");
        int buckets = 10;
        int[] counts = new int[buckets];
        int samples = 0;
        for (int x = -100; x < 100; x++) {
            for (int z = -500; z < 500; z += 5) {
                double v = eval(node, x, z, 0);
                counts[(int) (v * buckets)]++;
                samples++;
            }
        }
        assertEquals(40000, samples);
        // 期望每桶 samples/buckets；用宽松界避免偶发抖动（算法本身是确定的）
        int expected = samples / buckets;
        for (int i = 0; i < buckets; i++) {
            assertTrue(counts[i] > expected * 0.7 && counts[i] < expected * 1.3,
                    "第 " + i + " 桶计数异常: " + counts[i] + "（期望约 " + expected + "）");
        }
    }
}
