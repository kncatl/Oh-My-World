package com.kncatl.flatpattern.expr;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ExprParser {
    private final List<Token> tokens;
    private int pos;

    public ExprParser(List<Token> tokens) { this.tokens = tokens; this.pos = 0; }

    public ExprNode parse() { ExprNode node = conditional(); expect(TokenType.EOF); return node; }

    private ExprNode conditional() {
        ExprNode node = logicalOr();
        while (match(TokenType.QUESTION)) {
            ExprNode thenExpr = conditional();
            expect(TokenType.COLON);
            ExprNode elseExpr = conditional();
            node = new ExprNode.ConditionalNode(node, thenExpr, elseExpr);
        }
        return node;
    }

    private ExprNode logicalOr() {
        ExprNode left = logicalAnd();
        while (match(TokenType.OR)) left = new ExprNode.BinaryNode(left, ExprNode.BinaryOp.OR, logicalAnd());
        return left;
    }

    private ExprNode logicalAnd() {
        ExprNode left = comparison();
        while (match(TokenType.AND)) left = new ExprNode.BinaryNode(left, ExprNode.BinaryOp.AND, comparison());
        return left;
    }

    private ExprNode comparison() {
        ExprNode left = term();
        while (true) {
            if (match(TokenType.EQ)) left = new ExprNode.BinaryNode(left, ExprNode.BinaryOp.EQ, term());
            else if (match(TokenType.NE)) left = new ExprNode.BinaryNode(left, ExprNode.BinaryOp.NE, term());
            else if (match(TokenType.LT)) left = new ExprNode.BinaryNode(left, ExprNode.BinaryOp.LT, term());
            else if (match(TokenType.GT)) left = new ExprNode.BinaryNode(left, ExprNode.BinaryOp.GT, term());
            else if (match(TokenType.LE)) left = new ExprNode.BinaryNode(left, ExprNode.BinaryOp.LE, term());
            else if (match(TokenType.GE)) left = new ExprNode.BinaryNode(left, ExprNode.BinaryOp.GE, term());
            else break;
        }
        return left;
    }

    private ExprNode term() {
        ExprNode left = factor();
        while (true) {
            if (match(TokenType.PLUS)) left = new ExprNode.BinaryNode(left, ExprNode.BinaryOp.ADD, factor());
            else if (match(TokenType.MINUS)) left = new ExprNode.BinaryNode(left, ExprNode.BinaryOp.SUB, factor());
            else break;
        }
        return left;
    }

    private ExprNode factor() {
        ExprNode left = unary();
        while (true) {
            if (match(TokenType.STAR)) left = new ExprNode.BinaryNode(left, ExprNode.BinaryOp.MUL, unary());
            else if (match(TokenType.SLASH)) left = new ExprNode.BinaryNode(left, ExprNode.BinaryOp.DIV, unary());
            else if (match(TokenType.PERCENT)) left = new ExprNode.BinaryNode(left, ExprNode.BinaryOp.MOD, unary());
            else break;
        }
        return left;
    }

    private ExprNode unary() {
        if (match(TokenType.NOT)) return new ExprNode.UnaryNode(ExprNode.UnaryOp.NOT, unary());
        if (match(TokenType.MINUS)) {
            Token t = peek();
            if (t != null && t.type() == TokenType.NUMBER) { advance(); return new ExprNode.NumberNode(-Double.parseDouble(t.text())); }
            return new ExprNode.UnaryNode(ExprNode.UnaryOp.NEG, unary());
        }
        return primary();
    }

    private ExprNode primary() {
        if (match(TokenType.NUMBER)) return new ExprNode.NumberNode(Double.parseDouble(previous().text()));
        if (match(TokenType.BLOCK_ID)) return new ExprNode.BlockNode(previous().text());
        if (match(TokenType.IDENTIFIER)) {
            String name = previous().text();
            if (match(TokenType.LPAREN)) {
                List<ExprNode> args = new ArrayList<>();
                if (!check(TokenType.RPAREN)) { do { args.add(conditional()); } while (match(TokenType.COMMA)); }
                expect(TokenType.RPAREN);
                return new ExprNode.FuncCallNode(name, args);
            }
            return new ExprNode.VariableNode(name);
        }
        if (match(TokenType.LPAREN)) { ExprNode node = conditional(); expect(TokenType.RPAREN); return node; }
        if (match(TokenType.LBRACE)) {
            try { ExprNode block = parseBlock(); expect(TokenType.RBRACE); return block; }
            catch (Exception e) { throw new IllegalArgumentException("Block parse error: " + e.getMessage(), e); }
        }
        throw new IllegalArgumentException("Unexpected token: " + peek());
    }

    private boolean match(TokenType type) { if (pos < tokens.size() && tokens.get(pos).type() == type) { pos++; return true; } return false; }
    private boolean check(TokenType type) { return pos < tokens.size() && tokens.get(pos).type() == type; }
    private void expect(TokenType type) { if (!match(type)) throw new IllegalArgumentException("Expected " + type + " but got " + peek()); }
    private Token peek() { return pos < tokens.size() ? tokens.get(pos) : null; }
    private Token previous() { return tokens.get(pos - 1); }
    private void advance() { pos++; }

    private ExprNode parseBlock() {
        List<ExprNode.LetBinding> bindings = new ArrayList<>();
        Map<String, Integer> idx = new HashMap<>();
        while (true) {
            if (check(TokenType.RBRACE)) break;
            if (check(TokenType.IDENTIFIER) && peek().text().equals("let")) {
                advance();
                if (!match(TokenType.IDENTIFIER)) throw new IllegalArgumentException("Expected variable name after 'let'");
                String name = previous().text();
                expect(TokenType.ASSIGN);
                ExprNode value = doInline(conditional(), idx);
                match(TokenType.SEMI);
                idx.put(name, bindings.size());
                bindings.add(new ExprNode.LetBinding(name, value));
            } else {
                ExprNode body = doInline(conditional(), idx);
                return new ExprNode.BlockExprNode(bindings, body);
            }
        }
        return new ExprNode.BlockExprNode(bindings, new ExprNode.NumberNode(0));
    }

    private static ExprNode doInline(ExprNode node, Map<String, Integer> idx) {
        return switch (node) {
            case ExprNode.VariableNode v -> {
                Integer i = idx.get(v.name());
                yield i != null ? new ExprNode.IndexedVarNode(i) : node;
            }
            case ExprNode.BinaryNode b -> new ExprNode.BinaryNode(doInline(b.left(), idx), b.op(), doInline(b.right(), idx));
            case ExprNode.UnaryNode u -> new ExprNode.UnaryNode(u.op(), doInline(u.operand(), idx));
            case ExprNode.ConditionalNode c -> new ExprNode.ConditionalNode(doInline(c.condition(), idx), doInline(c.thenExpr(), idx), doInline(c.elseExpr(), idx));
            case ExprNode.FuncCallNode f -> {
                List<ExprNode> newArgs = new ArrayList<>();
                for (ExprNode a : f.args()) newArgs.add(doInline(a, idx));
                yield new ExprNode.FuncCallNode(f.name(), newArgs);
            }
            default -> node;
        };
    }
}
