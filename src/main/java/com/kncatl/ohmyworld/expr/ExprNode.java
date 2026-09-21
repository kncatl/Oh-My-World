package com.kncatl.ohmyworld.expr;

import java.util.List;

public sealed interface ExprNode {

    record NumberNode(double value) implements ExprNode {}
    record VariableNode(String name) implements ExprNode {}

    /**
     * 方块字面量。
     *
     * <p>解析结果缓存在 {@link #resolved}，避免每次求值都做一次
     * {@code ConcurrentHashMap} 的字符串查找——一个区块可达数万次。
     *
     * <p>缓存字段声明为 {@code Object} 而非 {@code BlockState}，是为了让本类在
     * 没有 Minecraft 类路径的环境（单元测试）里也能正常加载。取值方
     * （{@code ExprEvaluator}）负责类型转换。
     */
    final class BlockNode implements ExprNode {
        private final String blockId;
        private volatile Object resolved;

        public BlockNode(String blockId) { this.blockId = blockId; }

        public String blockId() { return blockId; }

        public Object resolved() { return resolved; }

        public void resolved(Object state) { this.resolved = state; }
    }
    record BinaryNode(ExprNode left, BinaryOp op, ExprNode right) implements ExprNode {}
    record UnaryNode(UnaryOp op, ExprNode operand) implements ExprNode {}
    record ConditionalNode(ExprNode condition, ExprNode thenExpr, ExprNode elseExpr) implements ExprNode {}
    record FuncCallNode(String name, List<ExprNode> args) implements ExprNode {}
    record BlockExprNode(List<LetBinding> bindings, ExprNode body) implements ExprNode {}
    record LetBinding(String name, ExprNode value) {}

    enum BinaryOp { ADD, SUB, MUL, DIV, MOD, EQ, NE, LT, GT, LE, GE, AND, OR }
    enum UnaryOp { NOT, NEG }
}
