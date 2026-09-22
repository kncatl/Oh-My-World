package com.kncatl.ohmyworld.expr;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 把经语义校验的 AST 编译成「按槽位访问变量」的形式。
 *
 * <p>求值器原本用 {@code HashMap<String, Object>} 保存 let 绑定：每个绑定要做
 * 数次 Map 操作，每次变量读取还要 {@code containsKey} + {@code get}，且每次进入
 * 块表达式都新建一个作用域（内含 HashMap）。对绑定较多的公式，每格因此要执行
 * 数百次哈希查找——而区块生成每区块要算近十万格。
 *
 * <p>编译期为每个绑定分配一个整数槽位后，变量访问变成定长数组读写，块表达式
 * 也不再需要作用域进出。槽位在整个表达式内唯一，因此遮蔽（内层同名绑定）
 * 在编译期就解析完毕，运行期无需保存/恢复。
 *
 * <p>编译期同时判定每个绑定是否与纵坐标相关（{@link ExprNode.CompiledBlockNode#hoisted()}）。
 * 表达式里只有 {@code ly} 能引用 y，因此「与 y 无关」可以精确静态判定；判定为无关的
 * 绑定由求值器每列只求值一次，逐格求值时跳过。{@code rand}/{@code randexcept} 经
 * {@code pickIndex(x, z, ly, …)} 隐式取用 ly，必须算作相关。
 *
 * <p>除提升外，本步骤不改变语义：绑定只可能在求值自己的值之后才被引用，且表达式无副作用。
 */
public final class ExprCompiler {

    /**
     * 全局唯一的槽位分配器。
     *
     * <p>槽位必须跨表达式唯一：与 y 无关的绑定会被提升到「每列求值一次」，其值在
     * 同一列的多次逐格求值之间保持有效。若不同表达式各自从 0 开始分配槽位，另一个
     * 表达式的求值就会覆盖这些值，而预备标记仍会显示「已预备」——结果静默出错。
     * 槽位随编译次数单调增长，但公式只在加载/切换时编译，量级很小；每个槽位在线程
     * 上下文里只占一个引用。
     */
    private static final AtomicInteger NEXT_SLOT = new AtomicInteger();

    /** 全局唯一的节点 id，供求值上下文记录「本节点在本列是否已预备」。嵌套块互不共用。 */
    private static final AtomicInteger NEXT_NODE_ID = new AtomicInteger();

    /** 编译期记录「某槽位是否与 ly 相关」；仅编译期间使用。 */
    private final Map<Integer, Boolean> slotDependent = new HashMap<>();

    /** 编译入口。传入已编译的节点会被原样返回（幂等）。 */
    public static ExprNode compile(ExprNode node) {
        return new ExprCompiler().compileNode(node, new ArrayDeque<>()).node();
    }

    /** 编译结果：节点本身，以及它是否依赖纵坐标（ly）。 */
    private record Compiled(ExprNode node, boolean lyDependent) {}

    private Compiled compileNode(ExprNode node, ArrayDeque<Map<String, Integer>> scopes) {
        return switch (node) {
            case ExprNode.NumberNode n -> new Compiled(n, false);
            case ExprNode.BlockNode b -> new Compiled(b, false);
            case ExprNode.VariableNode v -> {
                ExprNode resolved = resolve(v.name(), scopes);
                yield new Compiled(resolved, lyDependent(resolved));
            }

            case ExprNode.BinaryNode b -> {
                Compiled left = compileNode(b.left(), scopes);
                Compiled right = compileNode(b.right(), scopes);
                yield new Compiled(new ExprNode.BinaryNode(left.node(), b.op(), right.node()),
                        left.lyDependent() || right.lyDependent());
            }
            case ExprNode.UnaryNode u -> {
                Compiled operand = compileNode(u.operand(), scopes);
                yield new Compiled(new ExprNode.UnaryNode(u.op(), operand.node()), operand.lyDependent());
            }
            case ExprNode.ConditionalNode c -> {
                Compiled condition = compileNode(c.condition(), scopes);
                Compiled thenExpr = compileNode(c.thenExpr(), scopes);
                Compiled elseExpr = compileNode(c.elseExpr(), scopes);
                yield new Compiled(
                        new ExprNode.ConditionalNode(condition.node(), thenExpr.node(), elseExpr.node()),
                        condition.lyDependent() || thenExpr.lyDependent() || elseExpr.lyDependent());
            }

            case ExprNode.FuncCallNode f -> {
                List<ExprNode> args = new ArrayList<>(f.args().size());
                // rand/randexcept 经 pickIndex 隐式取用 ly，即使参数里不含 ly
                boolean dependent = f.name().equals("rand") || f.name().equals("randexcept");
                for (ExprNode arg : f.args()) {
                    Compiled compiled = compileNode(arg, scopes);
                    args.add(compiled.node());
                    dependent |= compiled.lyDependent();
                }
                List<ExprNode> compiledArgs = List.copyOf(args);
                // 函数名在编译期解析成编号：运行期不再有字符串比较、哈希查找与字符串 switch
                int id = ExprEvaluator.functionId(f.name());
                ExprNode call = id == ExprEvaluator.FN_UNKNOWN
                        ? new ExprNode.FuncCallNode(f.name(), compiledArgs)
                        : new ExprNode.CompiledFuncCallNode(id, compiledArgs);
                yield new Compiled(call, dependent);
            }

            case ExprNode.BlockExprNode be -> {
                Map<String, Integer> scope = new HashMap<>();
                scopes.push(scope);
                int count = be.bindings().size();
                int[] slots = new int[count];
                ExprNode[] values = new ExprNode[count];
                boolean[] hoisted = new boolean[count];
                for (int i = 0; i < count; i++) {
                    ExprNode.LetBinding binding = be.bindings().get(i);
                    // 绑定值先于绑定名可见，顺序不能颠倒
                    Compiled compiled = compileNode(binding.value(), scopes);
                    int slot = NEXT_SLOT.getAndIncrement();
                    values[i] = compiled.node();
                    slots[i] = slot;
                    slotDependent.put(slot, compiled.lyDependent());
                    // 与 ly 无关的绑定可提升：每列求值一次，逐格重算时跳过
                    hoisted[i] = !compiled.lyDependent();
                    scope.put(binding.name(), slot);
                }
                Compiled body = compileNode(be.body(), scopes);
                scopes.pop();
                yield new Compiled(
                        new ExprNode.CompiledBlockNode(
                                NEXT_NODE_ID.getAndIncrement(), slots, hoisted, values, body.node()),
                        body.lyDependent());
            }

            // 已编译的形态原样返回
            case ExprNode.BuiltinNode b -> new Compiled(b, b.kind() == 2);
            case ExprNode.SlotNode s -> new Compiled(s, lyDependent(s));
            case ExprNode.CompiledFuncCallNode cf -> new Compiled(cf, lyDependent(cf));
            // 已编译的块无法再反查槽位来源，保守视为与 y 相关：只放弃提升，不影响正确性
            case ExprNode.CompiledBlockNode cb -> new Compiled(cb, true);
        };
    }

    /** 编译形态下的 ly 相关性判定；无法追溯的槽位保守视为相关。 */
    private boolean lyDependent(ExprNode node) {
        return switch (node) {
            case ExprNode.NumberNode n -> false;
            case ExprNode.BlockNode b -> false;
            case ExprNode.BuiltinNode b -> b.kind() == 2;
            case ExprNode.SlotNode s -> slotDependent.getOrDefault(s.slot(), true);
            case ExprNode.BinaryNode b -> lyDependent(b.left()) || lyDependent(b.right());
            case ExprNode.UnaryNode u -> lyDependent(u.operand());
            case ExprNode.ConditionalNode c ->
                    lyDependent(c.condition()) || lyDependent(c.thenExpr()) || lyDependent(c.elseExpr());
            case ExprNode.FuncCallNode f -> {
                if (f.name().equals("rand") || f.name().equals("randexcept")) yield true;
                for (ExprNode arg : f.args()) {
                    if (lyDependent(arg)) yield true;
                }
                yield false;
            }
            case ExprNode.CompiledFuncCallNode f -> {
                if (f.id() == ExprEvaluator.FN_RAND || f.id() == ExprEvaluator.FN_RANDEXCEPT) yield true;
                for (ExprNode arg : f.args()) {
                    if (lyDependent(arg)) yield true;
                }
                yield false;
            }
            // 以下形态不会出现在编译后的节点里，只为了让 switch 穷尽并保持保守
            case ExprNode.VariableNode v -> true;
            case ExprNode.BlockExprNode be -> true;
            case ExprNode.CompiledBlockNode cb -> true;
        };
    }

    private ExprNode resolve(String name, ArrayDeque<Map<String, Integer>> scopes) {
        for (Map<String, Integer> scope : scopes) {
            Integer slot = scope.get(name);
            if (slot != null) return new ExprNode.SlotNode(slot);
        }
        return switch (name) {
            case "x" -> new ExprNode.BuiltinNode(0);
            case "z" -> new ExprNode.BuiltinNode(1);
            case "ly" -> new ExprNode.BuiltinNode(2);
            // 未定义的名字不会通过语义校验；运行期与旧的 builtinValue 一致地取 0
            default -> new ExprNode.BuiltinNode(-1);
        };
    }
}
