package com.kncatl.ohmyworld.expr;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
 * <p>本步骤不改变语义：绑定只可能在求值自己的值之后才被引用，且表达式无副作用。
 */
public final class ExprCompiler {

    private int nextSlot;

    /** 编译入口。传入已编译的节点会被原样返回（幂等）。 */
    public static ExprNode compile(ExprNode node) {
        return new ExprCompiler().compileNode(node, new ArrayDeque<>());
    }

    private ExprNode compileNode(ExprNode node, ArrayDeque<Map<String, Integer>> scopes) {
        return switch (node) {
            case ExprNode.NumberNode n -> n;
            case ExprNode.BlockNode b -> b;
            case ExprNode.VariableNode v -> resolve(v.name(), scopes);

            case ExprNode.BinaryNode b -> new ExprNode.BinaryNode(
                    compileNode(b.left(), scopes), b.op(), compileNode(b.right(), scopes));
            case ExprNode.UnaryNode u -> new ExprNode.UnaryNode(u.op(), compileNode(u.operand(), scopes));
            case ExprNode.ConditionalNode c -> new ExprNode.ConditionalNode(
                    compileNode(c.condition(), scopes),
                    compileNode(c.thenExpr(), scopes),
                    compileNode(c.elseExpr(), scopes));

            case ExprNode.FuncCallNode f -> {
                List<ExprNode> args = new ArrayList<>(f.args().size());
                for (ExprNode arg : f.args()) args.add(compileNode(arg, scopes));
                yield new ExprNode.FuncCallNode(f.name(), List.copyOf(args));
            }

            case ExprNode.BlockExprNode be -> {
                Map<String, Integer> scope = new HashMap<>();
                scopes.push(scope);
                int count = be.bindings().size();
                int[] slots = new int[count];
                ExprNode[] values = new ExprNode[count];
                for (int i = 0; i < count; i++) {
                    ExprNode.LetBinding binding = be.bindings().get(i);
                    // 绑定值先于绑定名可见，顺序不能颠倒
                    values[i] = compileNode(binding.value(), scopes);
                    slots[i] = nextSlot++;
                    scope.put(binding.name(), slots[i]);
                }
                ExprNode body = compileNode(be.body(), scopes);
                scopes.pop();
                yield new ExprNode.CompiledBlockNode(slots, values, body);
            }

            // 已编译的形态原样返回
            case ExprNode.BuiltinNode b -> b;
            case ExprNode.SlotNode s -> s;
            case ExprNode.CompiledBlockNode cb -> cb;
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
