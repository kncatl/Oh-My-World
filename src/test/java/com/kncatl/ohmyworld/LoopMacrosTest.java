package com.kncatl.ohmyworld;

import org.junit.jupiter.api.Test;

import com.kncatl.ohmyworld.expr.ExprEvaluator;
import com.kncatl.ohmyworld.expr.ExprLexer;
import com.kncatl.ohmyworld.expr.ExprNode;
import com.kncatl.ohmyworld.expr.ExprParser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 循环 / 位移宏（{@code sum/min/max(k,a,b,expr)} 与 {@code shift(expr,dx,dz)}）
 * 与空间助手（{@code slope/grad/curv/isodist}）的行为契约。
 *
 * <p>循环与位移在 {@link FormulaParser#expandLoops(ExprNode)} 里做编译期展开；
 * 空间助手由求值器实现（中心差分、步长 1 格）。
 */
class LoopMacrosTest {

    private static ExprNode expand(String source) {
        return FormulaParser.expandLoops(new ExprParser(ExprLexer.tokenize(source)).parse());
    }

    private static double eval(String source, int x, int z, int ly) {
        return ((Number) ExprEvaluator.eval(expand(source), x, z, ly)).doubleValue();
    }

    @Test
    void sumExpandsToAdditionChain() {
        assertEquals(10, eval("sum(k, 0, 4, k)", 0, 0, 0));
        assertEquals(10, eval("sum(k, -2, 2, k * k)", 0, 0, 0));
        assertEquals(0, eval("sum(k, 5, 5, k - 5)", 9, 9, 9));
    }

    @Test
    void minMaxLoopFormsFold() {
        assertEquals(0, eval("min(k, 0, 3, k * k)", 0, 0, 0));
        assertEquals(4, eval("max(k, 0, 3, (k - 2) * (k - 2))", 0, 0, 0));
        // 循环变量在展开后不影响外层同名绑定
        assertEquals(103, eval("{ let k = 100; sum(k, 0, 2, k) + k }", 0, 0, 0));
    }

    @Test
    void minMaxStillFoldWithoutLoopForm() {
        assertEquals(1, eval("min(3, 2, 1)", 0, 0, 0));
        assertEquals(3, eval("max(3, 2, 1)", 0, 0, 0));
        // 4 参折叠（表达式不引用首参变量）：min(h, 62, 70, y)
        assertEquals(62, eval("{ let h = 64; min(h, 62, 70, y) }", 0, 0, 100));
        assertEquals(3, eval("{ let h = 64; min(h, 62, 70, y) }", 0, 0, 3));
        // 4 参折叠 + 引用首参（但首参是 let 变量、非循环变量）同样按折叠
        assertEquals(1, eval("{ let h = 100; min(h, 5, 7, 1) }", 0, 0, 0));
    }

    @Test
    void nestedLoopsMultiplyWithinBudget() {
        assertEquals(24, eval("sum(k, 0, 3, sum(j, 0, 3, j))", 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> expand("sum(k, 0, 10, sum(j, 0, 10, k * j))"));
    }

    @Test
    void malformedLoopsReportClearErrors() {
        assertThrows(IllegalArgumentException.class, () -> expand("sum(k, 0, 100, k)"));
        assertThrows(IllegalArgumentException.class, () -> expand("sum(k, 0, 4.5, k)"));
        assertThrows(IllegalArgumentException.class, () -> expand("sum(k, 4, 0, k)"));
        assertThrows(IllegalArgumentException.class, () -> expand("sum(k, 0, 4)"));
        assertThrows(IllegalArgumentException.class, () -> expand("sum(x, 0, 4, x)"));
    }

    @Test
    void shiftSubstitutesXAndZ() {
        assertEquals(8, eval("shift(x, 5, 0)", 3, 0, 0));
        assertEquals(11, eval("shift(x, 5, 0) + x", 3, 0, 0));
        assertEquals(-2, eval("shift(z, 0, -2)", 0, 0, 0));
        // 与手动位移逐位一致
        ExprEvaluator.setWorldSeed(4242L);
        double shifted = eval("shift(noise2(x, z, 300, 1), 10, -7)", 13, 5, 0);
        double manual = ((Number) ExprEvaluator.eval(
                new ExprParser(ExprLexer.tokenize("noise2(x, z, 300, 1)")).parse(), 23, -2, 0)).doubleValue();
        assertEquals(manual, shifted, 0);
        // shift 只替换 x / z 两个符号；其它变量不受影响（含外层 let 绑定）
        assertEquals(2, eval("{ let a = 2; shift(a, 5, 0) }", 3, 0, 0));
        // 注意：x/z 的替换是纯语法的——若外层用 let 遮蔽了 x，替换后的 x 仍指向该绑定
        assertEquals(7, eval("{ let x = 2; shift(x, 5, 0) }", 3, 0, 0));
    }

    @Test
    void slopeGradAndCurvUseCentralDifferences() {
        assertEquals(2, eval("slope(x * 2)", 3, 7, 0));
        assertEquals(6, eval("slope(x * x)", 3, 7, 0));
        assertEquals(0, eval("slope(42)", 3, 7, 0));

        assertEquals(406, eval("{ let (gx, gz) = grad(x * x + z * z * 3); gx * 100 + gz }", 2, 1, 0));

        assertEquals(2, eval("curv(x * x)", 3, 7, 0));
        assertEquals(4, eval("curv(x * x + z * z)", 3, 7, 0));
        // ly 参与也不影响 x/z 差分的行为（这里 ly=5 只当作常量）
        assertEquals(2, eval("curv(x * x + ly)", 3, 7, 5));
    }

    @Test
    void isodistEstimatesDistanceToZeroContour() {
        assertEquals(3, eval("isodist(x - 7)", 10, 0, 0), 1e-9);
        assertEquals(5, eval("isodist(x * 2 - 10)", 0, 0, 0), 1e-9); // |0−10| / 2 = 5
        assertEquals(4, eval("isodist(x - 4)", 8, 0, 0), 1e-9);
    }
}
