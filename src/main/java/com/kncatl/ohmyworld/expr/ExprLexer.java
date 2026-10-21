package com.kncatl.ohmyworld.expr;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ExprLexer {

    private static final Pattern TOKEN_PATTERN = Pattern.compile(
            "([a-z][a-z0-9_.-]*:[a-z][a-z0-9/._-]*)" +
            "|([a-zA-Z_][a-zA-Z0-9_]*)" +
            "|([0-9]+(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?)" +
            "|(==|!=|<=|>=|&&|\\|\\|)" +
            "|([+\\-*/%<>=!?:;,(){}])" +
            "|(\\s+)"
    );

    public static List<Token> tokenize(String input) {
        List<Token> tokens = new ArrayList<>();
        Matcher m = TOKEN_PATTERN.matcher(input);
        int[] lineStarts = lineStarts(input);
        int lastEnd = 0;

        while (m.find()) {
            if (m.start() > lastEnd) {
                String unexpected = input.substring(lastEnd, m.start());
                throw new IllegalArgumentException("Unexpected character(s) at " + positionText(lineStarts, lastEnd)
                        + ": '" + unexpected.trim() + "'");
            }
            lastEnd = m.end();

            String blockId = m.group(1);
            String ident = m.group(2);
            String number = m.group(3);
            String multiOp = m.group(4);
            String singleOp = m.group(5);
            String space = m.group(6);

            if (space != null) continue;

            int tokenLine = lineAt(lineStarts, m.start());
            int tokenColumn = m.start() - lineStarts[tokenLine - 1] + 1;
            if (blockId != null) {
                tokens.add(new Token(TokenType.BLOCK_ID, blockId, m.start(), tokenLine, tokenColumn));
            } else if (ident != null) {
                tokens.add(new Token(TokenType.IDENTIFIER, ident, m.start(), tokenLine, tokenColumn));
            } else if (number != null) {
                tokens.add(new Token(TokenType.NUMBER, number, m.start(), tokenLine, tokenColumn));
            } else if (multiOp != null) {
                tokens.add(new Token(opType(multiOp), multiOp, m.start(), tokenLine, tokenColumn));
            } else if (singleOp != null) {
                tokens.add(new Token(opType(singleOp), singleOp, m.start(), tokenLine, tokenColumn));
            }
        }
        if (lastEnd < input.length()) {
            String unexpected = input.substring(lastEnd);
            throw new IllegalArgumentException("Unexpected character(s) at " + positionText(lineStarts, lastEnd)
                    + ": '" + unexpected.trim() + "'");
        }
        int eofLine = lineAt(lineStarts, input.length());
        tokens.add(new Token(TokenType.EOF, "", input.length(), eofLine,
                input.length() - lineStarts[eofLine - 1] + 1));
        return tokens;
    }

    /** 每个逻辑行起点（\n 之后）；用于 O(log n) 的 pos → line/column 换算。 */
    private static int[] lineStarts(String input) {
        List<Integer> starts = new ArrayList<>();
        starts.add(0);
        for (int i = 0; i < input.length(); i++) {
            if (input.charAt(i) == '\n') starts.add(i + 1);
        }
        int[] result = new int[starts.size()];
        for (int i = 0; i < result.length; i++) result[i] = starts.get(i);
        return result;
    }

    /** 1-based 行号：最后一个 lineStart <= pos。 */
    private static int lineAt(int[] lineStarts, int pos) {
        int lo = 0, hi = lineStarts.length - 1, answer = 0;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (lineStarts[mid] <= pos) { answer = mid; lo = mid + 1; }
            else hi = mid - 1;
        }
        return answer + 1;
    }

    private static String positionText(int[] lineStarts, int pos) {
        int line = lineAt(lineStarts, pos);
        return "line " + line + ", column " + (pos - lineStarts[line - 1] + 1);
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
            case "=" -> TokenType.ASSIGN;
            case "," -> TokenType.COMMA;
            case ";" -> TokenType.SEMI;
            case "(" -> TokenType.LPAREN;
            case ")" -> TokenType.RPAREN;
            case "{" -> TokenType.LBRACE;
            case "}" -> TokenType.RBRACE;
            default -> throw new IllegalArgumentException("Unknown operator: " + op);
        };
    }
}
