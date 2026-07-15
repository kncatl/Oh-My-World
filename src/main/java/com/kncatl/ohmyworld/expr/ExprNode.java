package com.kncatl.ohmyworld.expr;

import java.util.List;

public sealed interface ExprNode {
    record NumberNode(double value) implements ExprNode {}
    record VariableNode(String name) implements ExprNode {}
    record BlockNode(String blockId) implements ExprNode {}
    record BinaryNode(ExprNode left, BinaryOp op, ExprNode right) implements ExprNode {}
    record UnaryNode(UnaryOp op, ExprNode operand) implements ExprNode {}
    record ConditionalNode(ExprNode condition, ExprNode thenExpr, ExprNode elseExpr) implements ExprNode {}
    record FuncCallNode(String name, List<ExprNode> args) implements ExprNode {}
    record BlockExprNode(List<LetBinding> bindings, ExprNode body) implements ExprNode {}
    record IndexedVarNode(int index) implements ExprNode {}
    record LetBinding(String name, ExprNode value) {}
    enum BinaryOp { ADD, SUB, MUL, DIV, MOD, EQ, NE, LT, GT, LE, GE, AND, OR }
    enum UnaryOp { NOT, NEG }
}
