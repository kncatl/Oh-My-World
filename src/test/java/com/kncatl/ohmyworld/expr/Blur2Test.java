package com.kncatl.ohmyworld.expr;

import org.junit.jupiter.api.Test;

import com.kncatl.ohmyworld.FormulaParser;
import com.kncatl.ohmyworld.expr.ExprNode;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * blur2（1.3.2）的值语义：网格点 (2r+1)² 盒平均 + 双线性插值；
 * 线性表达式不受网格影响、对非对称场有精确的手算期望值；
 * 编译路径与直通路径一致。
 */
class Blur2Test {

    private static double evalCompiled(String source, int x, int z) {
        ExprNode node = ExprCompiler.compile(new ExprParser(ExprLexer.tokenize(source)).parse());
        return ((Number) ExprEvaluator.eval(node, x, z, 0)).doubleValue();
    }

    private static double evalRaw(String source, int x, int z) {
        ExprNode node = new ExprParser(ExprLexer.tokenize(source)).parse();
        return ((Number) ExprEvaluator.eval(node, x, z, 0)).doubleValue();
    }

    @Test
    void linearExpressionsPassThrough() {
        // 盒平均对线性项的偏差为 0（对称偏移抵消），插值又还原线性场
        for (int x = -7; x <= 7; x += 3) {
            for (int z = -7; z <= 7; z += 3) {
                assertEquals(x * 10 + z, evalCompiled("blur2(x * 10 + z, 2)", x, z), 1e-9);
                assertEquals(5, evalCompiled("blur2(2 + 3, 1, 8)", x, z), 1e-9);
            }
        }
    }

    @Test
    void asymmetricFieldHasExactHandComputedAverage() {
        // f(x,z) = |x - 100|；r=1、step=4：
        // 网格点 100 处的盒平均 = (4 + 0 + 4) * 3 / 9 = 8/3
        // 网格点 104 处 = (0 + 4 + 8) * 3 / 9 = 4
        assertEquals(8.0 / 3.0, evalCompiled("blur2(abs(x - 100), 1, 4)", 100, 100), 1e-9);
        assertEquals(4.0, evalCompiled("blur2(abs(x - 100), 1, 4)", 104, 100), 1e-9);
        // 中间位置按双线性插值：x=102 → 0.5 * (8/3 + 4) = 10/3
        assertEquals(10.0 / 3.0, evalCompiled("blur2(abs(x - 100), 1, 4)", 102, 100), 1e-9);
    }

    @Test
    void compiledMatchesRawPath() {
        for (int x = -6; x <= 6; x += 2) {
            for (int z = -6; z <= 6; z += 2) {
                assertEquals(evalRaw("blur2(noise2(x, z, 90, 3) + x * 0.37, 2)", x, z),
                        evalCompiled("blur2(noise2(x, z, 90, 3) + x * 0.37, 2)", x, z), 1e-12);
                assertEquals(evalRaw("blur2(fbm2(x, z, 300, 2, 6), 1, 8)", x, z),
                        evalCompiled("blur2(fbm2(x, z, 300, 2, 6), 1, 8)", x, z), 1e-12);
            }
        }
    }

    @Test
    void differentRadiusNodesKeepSeparateCaches() {
        // 同一表达式、不同 r：两个节点互不干扰（交错求值后仍各自正确）
        double a1 = evalCompiled("blur2(abs(x - 100), 1, 4)", 104, 100); // 期望 4
        double b1 = evalCompiled("blur2(abs(x - 100), 2, 4)", 104, 100); // 期望 (4+0+4+8+12)*5/25 = 5.6
        double a2 = evalCompiled("blur2(abs(x - 100), 1, 4)", 104, 100);
        double b2 = evalCompiled("blur2(abs(x - 100), 2, 4)", 104, 100);
        assertEquals(4.0, a1, 1e-9);
        assertEquals(5.6, b1, 1e-9);
        assertEquals(a1, a2, 1e-12);
        assertEquals(b1, b2, 1e-12);
    }
}
