package com.kncatl.ohmyworld.expr;

import java.util.Locale;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 校验 {@link ExprCompiler} 编译前后的求值结果一致。
 *
 * <p>槽位化会去掉运行期的名字查找与作用域进出，遮蔽（内层同名绑定）改由编译期
 * 解析。这是最容易出错的地方，因此覆盖到嵌套遮蔽、绑定自引用、条件分支内绑定
 * 等情形。
 */
class ExprCompilerTest {

    private static final String[] EXPRESSIONS = {
            "1",
            "x",
            "x + z * 2 - ly",
            "(x + z) % 2 < 1",
            "x > 0 && z > 0",
            "-x + !(z < 0)",
            "{ let a = x + z; a * 2 }",
            "{ let a = x; let b = a + 1; let c = b * 2; c % 7 }",
            // 遮蔽：内层 a 覆盖外层，块结束后外层应恢复
            "{ let a = 1; { let a = 2; a } + a }",
            "{ let a = 1; { let a = a + 10; a } }",
            // let 遮蔽内建坐标
            "{ let ly = 5; ly }",
            "{ let x = z; x + 1 }",
            "{ let ly = ly; ly + 1 }",
            // 条件分支里各自绑定
            "x > 0 ? { let t = z * 2; t } : { let u = ly; u }",
            "{ let base = x + ly; base > 10 ? base : base * 2 }",
            // 深嵌套
            "{ let a = 1; { let b = a + 1; { let c = b + 1; a + b + c } } }",
            // 绑定在条件中使用，且部分分支不引用
            "{ let a = x * 2; let b = z * 3; a > b ? a : b }",
            "sin(x * 0.1) * cos(z * 0.1) > 0",
            "floordiv(x, 3) + floormod(z, 5)",
    };

    private static String describe(Object value) {
        if (value instanceof Number n) return String.format(Locale.ROOT, "%.9f", n.doubleValue());
        if (value instanceof Boolean b) return b.toString();
        return String.valueOf(value);
    }

    @Test
    void compiledEvaluationMatchesUncompiled() {
        int checks = 0;
        for (String source : EXPRESSIONS) {
            ExprNode raw = new ExprParser(ExprLexer.tokenize(source)).parse();
            ExprNode compiled = ExprCompiler.compile(raw);

            for (int x = -3; x <= 3; x++) {
                for (int z = -3; z <= 3; z++) {
                    for (int ly = -3; ly <= 3; ly++) {
                        String expected = describe(ExprEvaluator.eval(raw, x, z, ly));
                        String actual = describe(ExprEvaluator.eval(compiled, x, z, ly));
                        if (!expected.equals(actual)) {
                            fail(String.format(Locale.ROOT,
                                    "表达式 %s 在 (x=%d, z=%d, ly=%d) 处结果不一致: 未编译=%s 编译后=%s",
                                    source, x, z, ly, expected, actual));
                        }
                        checks++;
                    }
                }
            }
        }
        assertEquals(EXPRESSIONS.length * 7 * 7 * 7, checks);
    }

    @Test
    void compilationIsIdempotent() {
        ExprNode raw = new ExprParser(ExprLexer.tokenize("{ let a = x; a + 1 }")).parse();
        ExprNode once = ExprCompiler.compile(raw);
        ExprNode twice = ExprCompiler.compile(once);

        for (int x = -2; x <= 2; x++) {
            assertEquals(describe(ExprEvaluator.eval(once, x, 0, 0)),
                    describe(ExprEvaluator.eval(twice, x, 0, 0)));
        }
    }

    private static ExprNode.CompiledBlockNode rootBlock(String source) {
        return (ExprNode.CompiledBlockNode) ExprCompiler.compile(
                new ExprParser(ExprLexer.tokenize(source)).parse());
    }

    @Test
    void hoistingFlagsFollowLyDependence() {
        // 与 y 无关的绑定可提升；直接或经更早绑定间接引用 ly 的不行
        assertArrayEquals(new boolean[]{true, false},
                rootBlock("{ let a = x + z; let b = a * ly; b }").hoisted());
        // let 可遮蔽 ly：绑定名在绑定值之后才生效
        assertArrayEquals(new boolean[]{true, true},
                rootBlock("{ let ly = 5; let b = ly + x; b }").hoisted());
        assertArrayEquals(new boolean[]{false, false},
                rootBlock("{ let a = ly; let b = a + 1; b }").hoisted());
        // rand/randexcept 经 pickIndex 隐式依赖 y，即使参数与 y 无关
        assertArrayEquals(new boolean[]{false},
                rootBlock("{ let r = rand(1, 2); r }").hoisted());
        // 条件分支与函数调用里的 ly 同样算相关
        assertArrayEquals(new boolean[]{true, false},
                rootBlock("{ let a = x; let b = a > 0 ? ly : 0; b }").hoisted());
        assertArrayEquals(new boolean[]{false},
                rootBlock("{ let a = sin(ly); a }").hoisted());
    }

    @Test
    void hoistingFlagsExistOnNestedBlocks() {
        ExprNode.CompiledBlockNode outer = rootBlock("{ let a = x; { let b = a + z; let c = b * ly; c } }");
        assertArrayEquals(new boolean[]{true}, outer.hoisted());

        ExprNode.CompiledBlockNode inner = (ExprNode.CompiledBlockNode) outer.body();
        assertArrayEquals(new boolean[]{true, false}, inner.hoisted());
    }

    /**
     * 提升后的绑定值在同一列内跨多次逐格求值存活，必须做到：
     * 1) 换列后重新预备；
     * 2) 穿插求值别的表达式不会污染本表达式的已预备值（槽位跨表达式全局唯一）。
     *
     * <p>这两点是 B3 最容易静默出错的地方，因此显式覆盖。
     */
    @Test
    void hoistedValuesSurviveInterleavedEvaluation() {
        String[] sources = {
                "{ let big = x * 1000 + z * 7; let r = ly > 0 ? big : big + 1; r }",
                "{ let t = z * 13 - x; let u = t * t; u + ly }",
                "{ let a = x + 1; let b = ly * 2; a + b }",
                "{ let ly = 3; let c = x * 5 + ly; c }",
        };
        ExprNode[] raw = new ExprNode[sources.length];
        ExprNode[] compiled = new ExprNode[sources.length];
        for (int i = 0; i < sources.length; i++) {
            raw[i] = new ExprParser(ExprLexer.tokenize(sources[i])).parse();
            compiled[i] = ExprCompiler.compile(raw[i]);
        }

        // 列外层遍历：同一 (x, z) 列里穿插多个表达式，并跨 ly 反复求值
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                for (int ly = -2; ly <= 2; ly++) {
                    for (int i = 0; i < sources.length; i++) {
                        String expected = describe(ExprEvaluator.eval(raw[i], x, z, ly));
                        String actual = describe(ExprEvaluator.eval(compiled[i], x, z, ly));
                        assertEquals(expected, actual, String.format(Locale.ROOT,
                                "%s 在 (x=%d, z=%d, ly=%d) 处结果不一致", sources[i], x, z, ly));
                    }
                }
            }
        }
    }

    /** 预先求值过的列被换出、再换回时，预备必须重新生效。 */
    @Test
    void returningToAPreparedColumnIsCorrect() {
        ExprNode compiled = ExprCompiler.compile(new ExprParser(ExprLexer.tokenize(
                "{ let a = x * 3 + z * 5; let b = ly % 7; a + b }")).parse());

        String first = describe(ExprEvaluator.eval(compiled, 11, -7, 3));
        assertEquals(first, describe(ExprEvaluator.eval(compiled, 11, -7, 3)));

        // 中间访问另一列，改变预备状态
        ExprEvaluator.eval(compiled, -4, 9, 8);

        assertEquals(first, describe(ExprEvaluator.eval(compiled, 11, -7, 3)));
    }

    /** 编译后的 block 形态必须保留提升标记（嵌套块也不应退化为逐格重算）。 */
    @Test
    void nestedHoistedBindingsAreStillHoisted() {
        ExprNode.CompiledBlockNode outer = rootBlock("{ let a = x * 2; { let b = a + 1; b * ly } }");
        assertTrue(outer.hoisted()[0]);

        ExprNode.CompiledBlockNode inner = (ExprNode.CompiledBlockNode) outer.body();
        assertTrue(inner.hoisted()[0]);
    }
}
