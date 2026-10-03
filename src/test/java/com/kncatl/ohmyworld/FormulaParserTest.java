package com.kncatl.ohmyworld;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link FormulaParser} 的语法/校验契约。
 *
 * <p>纯 JUnit 环境没有 Minecraft 注册表引导：任何方块字面量都会让解析抛
 * {@code NoClassDefFoundError}（`Blocks`/`BuiltInRegistries` 初始化失败，见
 * SESSION-HANDOVER 的踩坑记录与冒烟纪律）。因此这里全部使用"数值结果"的表达式：
 * 语义校验仍会走完整的变量/函数识别，只是最后报"图层必须返回方块"。
 *
 * <p>分节语法（多维度）落地后在 P3 扩展本测试。
 */
class FormulaParserTest {

    /** 断言「唯一错误是图层必须返回方块」——即表达式里的变量/函数本身都被正确识别。 */
    private static void assertOnlyReturnsBlockTypeError(String input) {
        FormulaParser.ParseResult result = FormulaParser.parseWithErrors(input);
        assertEquals(1, result.errors().size(), input + " -> " + result.errors());
        assertTrue(result.errors().get(0).contains("must return a block"),
                input + " -> " + result.errors());
        assertEquals(0, result.layers().size(), input);
    }

    @Test
    void seedIsRecognizedAsAVariable() {
        assertOnlyReturnsBlockTypeError("y=0: seed");
        assertOnlyReturnsBlockTypeError("y=0: seed > 0");
        assertOnlyReturnsBlockTypeError("y=0: { let seed = 7; seed }"); // let 遮蔽内建 seed
    }

    @Test
    void seedhashIsRecognizedAsAFunction() {
        assertOnlyReturnsBlockTypeError("y=0: seedhash()");
        assertOnlyReturnsBlockTypeError("y=0: seedhash(x, z, 0)");
        assertOnlyReturnsBlockTypeError("y=0: { let h = seedhash(x); h }");
    }

    @Test
    void seedhashRejectsNonNumberArguments() {
        FormulaParser.ParseResult result = FormulaParser.parseWithErrors("y=0: seedhash(x > 0)");
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("argument of seedhash")),
                result.errors().toString());
    }

    @Test
    void unknownNamesStillReportCorrectly() {
        FormulaParser.ParseResult variable = FormulaParser.parseWithErrors("y=0: seeds");
        assertEquals(1, variable.errors().size(), variable.errors().toString());
        assertTrue(variable.errors().get(0).contains("available: x, z, ly, seed"),
                variable.errors().toString());

        FormulaParser.ParseResult function = FormulaParser.parseWithErrors("y=0: seedhashes(x)");
        assertEquals(1, function.errors().size(), function.errors().toString());
        assertTrue(function.errors().get(0).contains("Unknown function"),
                function.errors().toString());
    }
}
