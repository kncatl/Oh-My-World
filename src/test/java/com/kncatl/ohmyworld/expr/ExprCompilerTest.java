package com.kncatl.ohmyworld.expr;

import java.util.Locale;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
