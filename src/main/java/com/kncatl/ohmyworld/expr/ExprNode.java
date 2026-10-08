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

    /**
     * let 绑定。{@code names} 通常只有一个；多个名字（如 {@code let (u, v) = warp2(...)}）
     * 表示绑定一个多返回函数的结果，此时 {@code value} 必须是 {@link TupleCallNode}。
     */
    record LetBinding(List<String> names, ExprNode value) {
        public LetBinding(String name, ExprNode value) { this(List.of(name), value); }
    }

    /**
     * 多返回函数的未编译调用（1.3.0）。多返回函数（warp2 / warp3 / noise2g /
     * worley2c / grad）只能出现在元组 let 的右侧；出现在其它位置会被语义校验拒绝。
     */
    record TupleCallNode(String name, List<ExprNode> args) implements ExprNode {}

    // --- 编译后的形态（由 ExprCompiler 生成）---------------------------------
    //
    // 求值器原本用 HashMap<String, Object> 保存 let 绑定，每次变量读取还要做
    // containsKey + get。对绑定较多的公式，每格要执行数百次哈希查找，而区块
    // 生成每区块要算近十万格。编译后变量访问变成定长数组的读写。

    /** 内建值：0=x，1=z，2=ly，3=seed，4=y（绝对 y），5=spawnx，6=spawnz，7=keep，
     *  8=sd，9=sdb，10=wd，11=slope（后五个只在 surface 行合法）。 */
    record BuiltinNode(int kind) implements ExprNode {}

    /** let 绑定的槽位引用。 */
    record SlotNode(int slot) implements ExprNode {}

    /**
     * 编译后的函数调用：{@code id} 是编译期解析出的函数编号（见
     * {@code ExprEvaluator.functionId}）。运行期不再对函数名做字符串比较与哈希查找。
     */
    record CompiledFuncCallNode(int id, List<ExprNode> args) implements ExprNode {}

    /** 编译后的多返回函数调用（1.3.0）。 */
    record CompiledTupleCallNode(int id, List<ExprNode> args) implements ExprNode {}

    /**
     * 编译后的元组分量读取（1.3.0）：从元组槽位 {@code slot} 取第 {@code index} 个分量。
     * {@code tupleDependent} 记录元组值是否与纵坐标相关（分量与其一致，决定能否提升）。
     */
    record TupleComponentNode(int slot, int index, boolean tupleDependent) implements ExprNode {}

    /**
     * 编译后的 cache2d（1.3.1）：世界对齐网格上的双线性插值。
     *
     * <p>值语义只由（网格角点、表达式、世界种子）决定——缓存只是每线程复用的
     * 性能优化，命中与否不改变结果。表达式在校验期已保证不引用 let 绑定、
     * ly/y 与视图类函数（见 FormulaParser）。
     */
    final class CompiledCache2dNode implements ExprNode {
        private final ExprNode expr;
        private final int step;
        private final ThreadLocal<CellCache> cache = ThreadLocal.withInitial(CellCache::new);

        public CompiledCache2dNode(ExprNode expr, int step) {
            this.expr = expr;
            this.step = step;
        }

        public ExprNode expr() { return expr; }

        public int step() { return step; }

        ThreadLocal<CellCache> cache() { return cache; }
    }

    /**
     * 编译后的 cache3d（1.3.1）：三维网格上的三线性插值（角点 y 为绝对 y）。
     * 语义与缓存约定同 {@link CompiledCache2dNode}。
     */
    final class CompiledCache3dNode implements ExprNode {
        private final ExprNode expr;
        private final int sx, sy, sz;
        private final ThreadLocal<CellCache> cache = ThreadLocal.withInitial(CellCache::new);

        public CompiledCache3dNode(ExprNode expr, int sx, int sy, int sz) {
            this.expr = expr;
            this.sx = sx;
            this.sy = sy;
            this.sz = sz;
        }

        public ExprNode expr() { return expr; }

        public int sx() { return sx; }

        public int sy() { return sy; }

        public int sz() { return sz; }

        ThreadLocal<CellCache> cache() { return cache; }
    }

    /**
     * 编译后的 let 块：按顺序求值 {@code values} 并写入对应 {@code slots}，再求值
     * {@code body}。槽位在编译期分配，因此无需作用域进出与名字查找。
     *
     * <p>{@code hoisted[i]} 为 true 表示第 i 个绑定与纵坐标无关，可以一列只求值一次：
     * 求值器在每列首次用到本节点时先算好这些绑定，逐格求值时直接跳过。这样既保留了
     * 逐格求值的高度相关内容，又不用为整层引入「与 y 无关」的额外约束。
     *
     * <p>{@code id} 用于区分「本节点在当前列是否已预备」——线程内的求值上下文按它
     * 记录预备状态。嵌套块各有各的 id，因此预备互不影响。
     */
    record CompiledBlockNode(int id, int[] slots, boolean[] hoisted, ExprNode[] values, ExprNode body)
            implements ExprNode {}

    enum BinaryOp { ADD, SUB, MUL, DIV, MOD, EQ, NE, LT, GT, LE, GE, AND, OR }
    enum UnaryOp { NOT, NEG }
}
