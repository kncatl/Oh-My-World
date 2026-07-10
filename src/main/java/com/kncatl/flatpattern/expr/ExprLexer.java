package com.kncatl.flatpattern.expr;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ExprLexer {

    private static final Pattern TOKEN_PATTERN = Pattern.compile(
            "([a-z][a-z0-9_.-]*:[a-z][a-z0-9/._-]*)" +
            "|([a-zA-Z_][a-zA-Z0-9_]*)" +
            "|([0-9]+(?:\\.[0-9]+)?)" +
            "|(==|!=|<=|>=|&&|\\|\\|)" +
            "|([+\\-*/%<>!?:,()])" +
            "|(\\s+)"
    );

    public static List<Token> tokenize(String input) {
        List<Token> tokens = new ArrayList<>();
        Matcher m = TOKEN_PATTERN.matcher(input);

        while (m.find()) {
            String blockId = m.group(1);
            String ident = m.group(2);
            String number = m.group(3);
            String multiOp = m.group(4);
            String singleOp = m.group(5);
            String space = m.group(6);

            if (space != null) continue;

            if (blockId != null) {
                tokens.add(new Token(TokenType.BLOCK_ID, blockId, m.start()));
            } else if (ident != null) {
                tokens.add(new Token(TokenType.IDENTIFIER, ident, m.start()));
            } else if (number != null) {
                tokens.add(new Token(TokenType.NUMBER, number, m.start()));
            } else if (multiOp != null) {
                tokens.add(new Token(opType(multiOp), multiOp, m.start()));
            } else if (singleOp != null) {
                tokens.add(new Token(opType(singleOp), singleOp, m.start()));
            }
        }
        tokens.add(new Token(TokenType.EOF, "", input.length()));
        return tokens;
    }

    private static TokenType opType(String op) {
        return switch (op) {
            case "+" -> TokenType.PLUS;
            case "-" -> TokenType.MINUS;
            case "*" -> TokenType.STAR;
            case "/" -> TokenType.SLASH;
            case "%" -> TokenType.PERCENT;
            case "==" -> TokenType.EQ;
            case "!=" -> TokenType.NE;
            case "<" -> TokenType.LT;
            case ">" -> TokenType.GT;
            case "<=" -> TokenType.LE;
            case ">=" -> TokenType.GE;
            case "&&" -> TokenType.AND;
            case "||" -> TokenType.OR;
            case "!" -> TokenType.NOT;
            case "?" -> TokenType.QUESTION;
            case ":" -> TokenType.COLON;
            case "," -> TokenType.COMMA;
            case "(" -> TokenType.LPAREN;
            case ")" -> TokenType.RPAREN;
            default -> throw new IllegalArgumentException("Unknown operator: " + op);
        };
    }
}
