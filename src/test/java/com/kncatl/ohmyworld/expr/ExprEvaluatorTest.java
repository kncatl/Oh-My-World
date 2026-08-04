package com.kncatl.ohmyworld.expr;

import com.kncatl.ohmyworld.FormulaParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ExprEvaluatorTest {

    @Test
    void nestedLetKeepsOuterBindings() {
        ExprNode expression = new ExprParser(ExprLexer.tokenize(
                "{ let a = 5; { let b = 3; a * b } }"))
                .parse();

        Object result = ExprEvaluator.eval(expression, 0, 0, 0);

        assertEquals(15.0d, ((Number) result).doubleValue(), 0.0d);
    }

    @Test
    void letBindingsCanShadowBuiltInCoordinates() {
        ExprNode expression = new ExprParser(ExprLexer.tokenize(
                "{ let x = 5; x + 1 }"))
                .parse();

        Object result = ExprEvaluator.eval(expression, 100, 0, 0);

        assertEquals(6.0d, ((Number) result).doubleValue(), 0.0d);
    }

    @Test
    void nonBlockLayerResultsAreRejected() {
        FormulaParser.ParseResult result = FormulaParser.parseWithErrors("y=0: 1");

        assertEquals(0, result.layers().size());
        assertEquals(1, result.errors().size());
    }

}
