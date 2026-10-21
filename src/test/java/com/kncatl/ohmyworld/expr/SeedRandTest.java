package com.kncatl.ohmyworld.expr;

import com.kncatl.ohmyworld.FormulaParser;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code seedrand(...)} 的种子相关契约。
 *
 * <p>方块选择本身依赖注册表，纯 JUnit 环境只锁定哈希索引：同一世界/坐标确定，
 * 换种子得到另一套图案，且索引始终落在池范围内。
 */
class SeedRandTest {

    @Test
    void seededIndexIsDeterministic() {
        ExprEvaluator.setWorldSeed(987654321L);
        for (int i = -30; i <= 30; i++) {
            int a = ExprEvaluator.pickSeededIndex(i, -i, 17, 11);
            int b = ExprEvaluator.pickSeededIndex(i, -i, 17, 11);
            assertEquals(a, b, "同一世界、坐标和 ly 必须得到同一索引");
        }
    }

    @Test
    void seededIndexStaysInRange() {
        ExprEvaluator.setWorldSeed(-7138994955873085613L);
        for (int bound : new int[]{1, 2, 3, 7, 16, 97}) {
            for (int x = -50; x <= 50; x += 9) {
                for (int y = -20; y <= 20; y += 7) {
                    int index = ExprEvaluator.pickSeededIndex(x, -x, y, bound);
                    assertTrue(index >= 0 && index < bound,
                            "索引越界: " + index + " / " + bound);
                }
            }
        }
    }

    @Test
    void differentSeedsProduceDifferentPatterns() {
        List<Integer> first = indicesForSeed(12345L);
        List<Integer> second = indicesForSeed(-987654321L);
        assertNotEquals(first, second, "不同世界种子应产生不同的方块随机图案");
    }

    @Test
    void changingOnlyLowSeedBitChangesThePattern() {
        // seedhash 要求覆盖完整 64 位；seedrand 同样不能只使用截断后的种子
        List<Integer> first = indicesForSeed(0L);
        List<Integer> second = indicesForSeed(1L);
        assertNotEquals(first, second);
    }

    private static List<Integer> indicesForSeed(long seed) {
        ExprEvaluator.setWorldSeed(seed);
        List<Integer> result = new ArrayList<>();
        for (int x = -8; x <= 8; x++) {
            for (int z = -8; z <= 8; z++) {
                result.add(ExprEvaluator.pickSeededIndex(x, z, 3, 5));
            }
        }
        return result;
    }

    @Test
    void seedrandIsRecognizedAsABlockRandomFunction() {
        FormulaParser.ParseResult result = FormulaParser.parseWithErrors("y=0: seedrand()");
        assertTrue(result.errors().isEmpty(), result.errors().toString());
        assertEquals(1, result.layers().size());
    }
}
