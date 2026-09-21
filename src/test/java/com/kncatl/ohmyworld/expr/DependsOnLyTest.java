package com.kncatl.ohmyworld.expr;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 校验 {@link ExprEvaluator#dependsOnLy} 的判定。
 *
 * <p>该判定决定「同一列能否只求值一次」，判错会导致世界方块错位，
 * 因此覆盖到 let 遮蔽、嵌套作用域、rand 隐式依赖等边界。
 */
class DependsOnLyTest {

    private static boolean depends(String expr) {
        return ExprEvaluator.dependsOnLy(new ExprParser(ExprLexer.tokenize(expr)).parse());
    }

    @Test
    void plainCoordinatesAreColumnInvariant() {
        assertFalse(depends("x + z"));
        assertFalse(depends("(x + z) % 2 < 1"));
        assertFalse(depends("sin(x * 0.1) * cos(z * 0.1) > 0"));
        assertFalse(depends("x > 8 ? 1 : 2"));
        assertFalse(depends("1"));
    }

    @Test
    void lyMakesItColumnVarying() {
        assertTrue(depends("ly"));
        assertTrue(depends("x + ly"));
        assertTrue(depends("x > 0 ? 1 : ly"));
        assertTrue(depends("x > 0 ? ly : 1"));
        assertTrue(depends("x > 0 ? 1 : (ly + 1)"));
        assertTrue(depends("-ly"));
        assertTrue(depends("sin(ly)"));
    }

    @Test
    void letBindingShadowsLy() {
        // `let ly = ...` 之后的名字指向绑定值，与纵坐标无关
        assertFalse(depends("{ let ly = 5; ly }"));
        assertFalse(depends("{ let ly = x; ly + 1 }"));
        // 绑定值在绑定名生效之前求值，此时 ly 仍指向内建坐标
        assertTrue(depends("{ let ly = ly; ly }"));
        assertTrue(depends("{ let a = ly; a }"));
    }

    @Test
    void nestedScopesDoNotLeakShadowing() {
        // 内层绑定不应影响外层
        assertTrue(depends("{ let a = 1; { let ly = 2; ly } + ly }"));
        // 内层继承外层的遮蔽
        assertFalse(depends("{ let ly = 1; { let b = ly; b } }"));
        // 兄弟表达式看到先前绑定
        assertFalse(depends("{ let ly = 1; let a = ly; a }"));
    }

    @Test
    void randomFunctionsAreTreatedAsColumnVarying() {
        // pickIndex 以 ly 作为散列输入，因此结果是 y 相关的
        assertTrue(depends("rand(minecraft:stone, minecraft:dirt)"));
        assertTrue(depends("randexcept(minecraft:stone)"));
        // 出现在子表达式里同样要算作 y 相关
        assertTrue(depends("x > 0 ? rand(1, 2) : 3"));
    }
}
