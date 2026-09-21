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

    // --- 编译后的形态（由 ExprCompiler 生成）---------------------------------
    //
    // 求值器原本用 HashMap<String, Object> 保存 let 绑定，每次变量读取还要做
    // containsKey + get。对绑定较多的公式，每格要执行数百次哈希查找，而区块
    // 生成每区块要算近十万格。编译后变量访问变成定长数组的读写。

    /** 内建坐标：0=x，1=z，2=ly。 */
    record BuiltinNode(int kind) implements ExprNode {}

    /** let 绑定的槽位引用。 */
    record SlotNode(int slot) implements ExprNode {}

    /**
     * 编译后的 let 块：按顺序求值 {@code values} 并写入对应 {@code slots}，再求值
     * {@code body}。槽位在编译期分配，因此无需作用域进出与名字查找。
     */
    record CompiledBlockNode(int[] slots, ExprNode[] values, ExprNode body) implements ExprNode {}

    enum BinaryOp { ADD, SUB, MUL, DIV, MOD, EQ, NE, LT, GT, LE, GE, AND, OR }
    enum UnaryOp { NOT, NEG }
}
