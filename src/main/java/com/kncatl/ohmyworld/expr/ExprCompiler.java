package com.kncatl.ohmyworld.expr;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
                // 1.3.1：cache2d/cache3d 编译为带线程本地缓存的节点（语义=角点插值）
                Compiled cache = compileCacheNode(f.name(), f.args(), compiledArgs, dependent);
                if (cache != null) yield cache;
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
                List<Integer> slotList = new ArrayList<>();
                List<ExprNode> valueList = new ArrayList<>();
                List<Boolean> hoistedList = new ArrayList<>();
                for (ExprNode.LetBinding binding : be.bindings()) {
                    // 绑定值先于绑定名可见，顺序不能颠倒
                    Compiled compiled = compileNode(binding.value(), scopes);
                    boolean dependent = compiled.lyDependent();
                    if (binding.names().size() == 1) {
                        int slot = NEXT_SLOT.getAndIncrement();
                        valueList.add(compiled.node());
                        slotList.add(slot);
                        slotDependent.put(slot, dependent);
                        // 与 ly 无关的绑定可提升：每列求值一次，逐格重算时跳过
                        hoistedList.add(!dependent);
                        scope.put(binding.names().get(0), slot);
                    } else {
                        // 元组 let：一个隐藏槽位存元组值，每个名字一个分量槽位。
                        // 分量与元组同享 ly 相关性（一起提升或一起逐格重算）。
                        int tupleSlot = NEXT_SLOT.getAndIncrement();
                        valueList.add(compiled.node());
                        slotList.add(tupleSlot);
                        slotDependent.put(tupleSlot, dependent);
                        hoistedList.add(!dependent);
                        for (int i = 0; i < binding.names().size(); i++) {
                            int slot = NEXT_SLOT.getAndIncrement();
                            valueList.add(new ExprNode.TupleComponentNode(tupleSlot, i, dependent));
                            slotList.add(slot);
                            slotDependent.put(slot, dependent);
                            hoistedList.add(!dependent);
                            scope.put(binding.names().get(i), slot);
                        }
                    }
                }
                int count = slotList.size();
                int[] slots = new int[count];
                ExprNode[] values = new ExprNode[count];
                boolean[] hoisted = new boolean[count];
                for (int i = 0; i < count; i++) {
                    slots[i] = slotList.get(i);
                    values[i] = valueList.get(i);
                    hoisted[i] = hoistedList.get(i);
                }
                Compiled body = compileNode(be.body(), scopes);
                scopes.pop();
                yield new Compiled(
                        new ExprNode.CompiledBlockNode(
                                NEXT_NODE_ID.getAndIncrement(), slots, hoisted, values, body.node()),
                        body.lyDependent());
            }

            case ExprNode.TupleCallNode t -> {
                List<ExprNode> args = new ArrayList<>(t.args().size());
                boolean dependent = false;
                for (ExprNode arg : t.args()) {
                    Compiled compiled = compileNode(arg, scopes);
                    args.add(compiled.node());
                    dependent |= compiled.lyDependent();
                }
                int id = ExprEvaluator.tupleFunctionId(t.name());
                ExprNode call = id == ExprEvaluator.FN_UNKNOWN
                        ? new ExprNode.TupleCallNode(t.name(), List.copyOf(args))
                        : new ExprNode.CompiledTupleCallNode(id, List.copyOf(args));
                yield new Compiled(call, dependent);
            }

            // 已编译的形态原样返回
            case ExprNode.BuiltinNode b ->
                    new Compiled(b, b.kind() == 2 || b.kind() == 4 || b.kind() >= 8);
            case ExprNode.SlotNode s -> new Compiled(s, lyDependent(s));
            case ExprNode.CompiledFuncCallNode cf -> new Compiled(cf, lyDependent(cf));
            case ExprNode.CompiledTupleCallNode ct -> new Compiled(ct, lyDependent(ct));
            case ExprNode.TupleComponentNode tc -> new Compiled(tc, tc.tupleDependent());
            // cache2d 与纵坐标无关；cache3d 可能引用 y（保守视为相关，只放弃提升）
            case ExprNode.CompiledCache2dNode c2 -> new Compiled(c2, false);
            case ExprNode.CompiledCache3dNode c3 -> new Compiled(c3, true);
            // 已编译的块无法再反查槽位来源，保守视为与 y 相关：只放弃提升，不影响正确性
            case ExprNode.CompiledBlockNode cb -> new Compiled(cb, true);
        };
    }

    /**
     * 尝试把 cache2d/cache3d 调用编译为带缓存的专用节点（1.3.1）。
     *
     * <p>只有能证明缓存安全的形态才会转换：表达式不引用 ly（cache2d 也不允许 y）、
     * 不含 rand，且 step 为 1..16 的整数字面量。未转换时保留普通函数调用
     * （运行期走无缓存直通实现，语义完全相同）。
     */
    private static Compiled compileCacheNode(String name, List<ExprNode> rawArgs, List<ExprNode> args,
                                             boolean dependent) {
        if (!name.equals("cache2d") && !name.equals("cache3d")) return null;
        if (rawArgs.isEmpty() || args.isEmpty()) return null;
        // 表达式必须自包含：自由变量只能是内建量（不得引用 let / 元组槽位）。
        // 缓存键只看角点坐标——若表达式引用外层绑定，跨列复用会串值。
        if (referencesNonBuiltinVariables(rawArgs.get(0))) return null;
        if (name.equals("cache2d")) {
            // cache2d 的值不得随纵坐标变化：dependent 覆盖 ly / y / rand
            if (dependent || args.size() > 2) return null;
            int step = args.size() == 2 ? literalStep(rawArgs.get(1)) : 4;
            if (step <= 0) return null;
            return new Compiled(new ExprNode.CompiledCache2dNode(args.get(0), step), false);
        }
        if (args.size() != 1 && args.size() != 4) return null;
        // cache3d 不允许 ly/rand（允许 y：角点求值使用各自角点的绝对 y）
        if (usesVertical(rawArgs.get(0), false)) return null;
        int sx = 4, sy = 8, sz = 4;
        if (args.size() == 4) {
            sx = literalStep(rawArgs.get(1));
            sy = literalStep(rawArgs.get(2));
            sz = literalStep(rawArgs.get(3));
            if (sx <= 0 || sy <= 0 || sz <= 0) return null;
        }
        return new Compiled(new ExprNode.CompiledCache3dNode(args.get(0), sx, sy, sz), dependent);
    }

    /** 1..16 的整数字面量；否则 -1（仅编译期使用）。 */
    private static int literalStep(ExprNode node) {
        if (node instanceof ExprNode.NumberNode n
                && n.value() == Math.rint(n.value())
                && n.value() >= 1 && n.value() <= 16) {
            return (int) n.value();
        }
        return -1;
    }

    /** 编译期允许出现在自包含表达式里的内建量（cache2d/cache3d 的自由变量白名单）。 */
    private static final Set<String> BUILTIN_NAMES =
            Set.of("x", "y", "z", "ly", "seed", "spawnx", "spawnz");

    /** 未编译 AST 是否引用非内建的自由变量（即 let 绑定；考虑遮蔽）。 */
    private static boolean referencesNonBuiltinVariables(ExprNode node) {
        return referencesNonBuiltinVariables(node, new HashSet<>());
    }

    private static boolean referencesNonBuiltinVariables(ExprNode node, Set<String> shadowed) {
        return switch (node) {
            case ExprNode.NumberNode n -> false;
            case ExprNode.BlockNode b -> false;
            case ExprNode.VariableNode v ->
                    !shadowed.contains(v.name()) && !BUILTIN_NAMES.contains(v.name());
            case ExprNode.BinaryNode b -> referencesNonBuiltinVariables(b.left(), shadowed)
                    || referencesNonBuiltinVariables(b.right(), shadowed);
            case ExprNode.UnaryNode u -> referencesNonBuiltinVariables(u.operand(), shadowed);
            case ExprNode.ConditionalNode c -> referencesNonBuiltinVariables(c.condition(), shadowed)
                    || referencesNonBuiltinVariables(c.thenExpr(), shadowed)
                    || referencesNonBuiltinVariables(c.elseExpr(), shadowed);
            case ExprNode.FuncCallNode f -> f.args().stream()
                    .anyMatch(arg -> referencesNonBuiltinVariables(arg, shadowed));
            case ExprNode.TupleCallNode t -> t.args().stream()
                    .anyMatch(arg -> referencesNonBuiltinVariables(arg, shadowed));
            case ExprNode.BlockExprNode be -> {
                Set<String> inner = new HashSet<>(shadowed);
                boolean found = false;
                for (ExprNode.LetBinding binding : be.bindings()) {
                    if (referencesNonBuiltinVariables(binding.value(), inner)) {
                        found = true;
                        break;
                    }
                    inner.addAll(binding.names());
                }
                yield found || referencesNonBuiltinVariables(be.body(), inner);
            }
            case ExprNode.BuiltinNode b -> false;
            // 未编译 AST 不应含以下形态；保守视为有引用（不转换）
            case ExprNode.SlotNode s -> true;
            case ExprNode.CompiledFuncCallNode cf -> true;
            case ExprNode.CompiledTupleCallNode ct -> true;
            case ExprNode.TupleComponentNode tc -> true;
            case ExprNode.CompiledCache2dNode c2 -> true;
            case ExprNode.CompiledCache3dNode c3 -> true;
            case ExprNode.CompiledBlockNode cb -> true;
        };
    }

    /** 未编译 AST 是否引用 ly / rand（includeY 时也包含 y）；考虑 let 遮蔽。 */
    public static boolean usesVertical(ExprNode node, boolean includeY) {
        return usesVertical(node, includeY, new HashSet<>());
    }

    private static boolean usesVertical(ExprNode node, boolean includeY, Set<String> shadowed) {
        return switch (node) {
            case ExprNode.NumberNode n -> false;
            case ExprNode.BlockNode b -> false;
            case ExprNode.VariableNode v -> !shadowed.contains(v.name())
                    && (v.name().equals("ly") || (includeY && v.name().equals("y")));
            case ExprNode.BinaryNode b ->
                    usesVertical(b.left(), includeY, shadowed) || usesVertical(b.right(), includeY, shadowed);
            case ExprNode.UnaryNode u -> usesVertical(u.operand(), includeY, shadowed);
            case ExprNode.ConditionalNode c ->
                    usesVertical(c.condition(), includeY, shadowed)
                            || usesVertical(c.thenExpr(), includeY, shadowed)
                            || usesVertical(c.elseExpr(), includeY, shadowed);
            case ExprNode.FuncCallNode f -> {
                if (f.name().equals("rand") || f.name().equals("randexcept")) yield true;
                for (ExprNode arg : f.args()) {
                    if (usesVertical(arg, includeY, shadowed)) yield true;
                }
                yield false;
            }
            case ExprNode.TupleCallNode t -> {
                for (ExprNode arg : t.args()) {
                    if (usesVertical(arg, includeY, shadowed)) yield true;
                }
                yield false;
            }
            case ExprNode.BlockExprNode be -> {
                Set<String> inner = new HashSet<>(shadowed);
                for (ExprNode.LetBinding binding : be.bindings()) {
                    if (usesVertical(binding.value(), includeY, inner)) yield true;
                    inner.addAll(binding.names());
                }
                yield usesVertical(be.body(), includeY, inner);
            }
            case ExprNode.BuiltinNode b -> b.kind() == 2 || (includeY && b.kind() == 4);
            // 未编译 AST 不应含以下形态；保守视为与纵坐标相关
            case ExprNode.SlotNode s -> true;
            case ExprNode.CompiledFuncCallNode cf -> true;
            case ExprNode.CompiledTupleCallNode ct -> true;
            case ExprNode.TupleComponentNode tc -> tc.tupleDependent();
            case ExprNode.CompiledBlockNode cb -> true;
            case ExprNode.CompiledCache2dNode c2 -> false;
            case ExprNode.CompiledCache3dNode c3 -> true;
        };
    }

    /** 编译形态下的 ly 相关性判定；无法追溯的槽位保守视为相关。 */
    private boolean lyDependent(ExprNode node) {
        return switch (node) {
            case ExprNode.NumberNode n -> false;
            case ExprNode.BlockNode b -> false;
            case ExprNode.BuiltinNode b -> b.kind() == 2 || b.kind() == 4 || b.kind() >= 8;
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
            case ExprNode.TupleCallNode t -> {
                for (ExprNode arg : t.args()) {
                    if (lyDependent(arg)) yield true;
                }
                yield false;
            }
            case ExprNode.CompiledTupleCallNode t -> {
                for (ExprNode arg : t.args()) {
                    if (lyDependent(arg)) yield true;
                }
                yield false;
            }
            case ExprNode.TupleComponentNode t -> t.tupleDependent();
            case ExprNode.CompiledCache2dNode c2 -> false;
            // cache3d 可能引用 y；已编译后无法区分，保守视为相关（只放弃提升）
            case ExprNode.CompiledCache3dNode c3 -> true;
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
            case "seed" -> new ExprNode.BuiltinNode(3);
            case "y" -> new ExprNode.BuiltinNode(4);
            case "spawnx" -> new ExprNode.BuiltinNode(5);
            case "spawnz" -> new ExprNode.BuiltinNode(6);
            // 表面通道的 keep 与 sd/sdb/wd/slope（语义校验只在 surface 行放行）
            case "keep" -> new ExprNode.BuiltinNode(7);
            case "sd" -> new ExprNode.BuiltinNode(8);
            case "sdb" -> new ExprNode.BuiltinNode(9);
            case "wd" -> new ExprNode.BuiltinNode(10);
            case "slope" -> new ExprNode.BuiltinNode(11);
            // 未定义的名字不会通过语义校验；运行期与旧的 builtinValue 一致地取 0
            default -> new ExprNode.BuiltinNode(-1);
        };
    }
}
