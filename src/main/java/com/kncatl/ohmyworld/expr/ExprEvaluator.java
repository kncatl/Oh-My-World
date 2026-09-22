package com.kncatl.ohmyworld.expr;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import com.kncatl.ohmyworld.expr.ExprNode.BinaryOp;

public class ExprEvaluator {

    public enum ValueType { NUMBER, BOOLEAN, BLOCK, UNKNOWN }

    private static final ThreadLocal<EvalContext> CONTEXT = ThreadLocal.withInitial(EvalContext::new);

    private static final Map<String, Integer> FUNCTION_ARITY = Map.ofEntries(
            Map.entry("floordiv", 2), Map.entry("floormod", 2),
            Map.entry("abs", 1), Map.entry("max", 2), Map.entry("min", 2),
            Map.entry("floor", 1), Map.entry("ceil", 1), Map.entry("round", 1),
            Map.entry("sign", 1), Map.entry("sqrt", 1), Map.entry("pow", 2),
            Map.entry("exp", 1), Map.entry("log", 1), Map.entry("log10", 1),
            Map.entry("sin", 1), Map.entry("cos", 1), Map.entry("tan", 1),
            Map.entry("asin", 1), Map.entry("acos", 1), Map.entry("atan", 1),
            Map.entry("todeg", 1), Map.entry("torad", 1),
            Map.entry("rand", -1), Map.entry("randexcept", -1));

    // 编译后的函数编号。ExprCompiler 在编译期把函数名解析成这些常量，
    // 运行期因此不再对函数名做字符串比较与哈希查找（每次调用都可观）。
    public static final int FN_FLOORDIV = 0, FN_FLOORMOD = 1, FN_ABS = 2, FN_MAX = 3, FN_MIN = 4,
            FN_FLOOR = 5, FN_CEIL = 6, FN_ROUND = 7, FN_SIGN = 8, FN_SQRT = 9, FN_POW = 10,
            FN_EXP = 11, FN_LOG = 12, FN_LOG10 = 13, FN_SIN = 14, FN_COS = 15, FN_TAN = 16,
            FN_ASIN = 17, FN_ACOS = 18, FN_ATAN = 19, FN_TODEG = 20, FN_TORAD = 21;
    public static final int FN_RAND = 100, FN_RANDEXCEPT = 101;
    /** 未知函数：编译期保留原名，运行期仍按原来的方式报错。 */
    public static final int FN_UNKNOWN = -1;

    /**
     * 编译期把函数名解析成编号；未知函数返回 {@link #FN_UNKNOWN}。
     * 每个调用点只在编译期解析一次，因此这里用字符串 switch 即可。
     */
    public static int functionId(String name) {
        return switch (name) {
            case "floordiv" -> FN_FLOORDIV;
            case "floormod" -> FN_FLOORMOD;
            case "abs" -> FN_ABS;
            case "max" -> FN_MAX;
            case "min" -> FN_MIN;
            case "floor" -> FN_FLOOR;
            case "ceil" -> FN_CEIL;
            case "round" -> FN_ROUND;
            case "sign" -> FN_SIGN;
            case "sqrt" -> FN_SQRT;
            case "pow" -> FN_POW;
            case "exp" -> FN_EXP;
            case "log" -> FN_LOG;
            case "log10" -> FN_LOG10;
            case "sin" -> FN_SIN;
            case "cos" -> FN_COS;
            case "tan" -> FN_TAN;
            case "asin" -> FN_ASIN;
            case "acos" -> FN_ACOS;
            case "atan" -> FN_ATAN;
            case "todeg" -> FN_TODEG;
            case "torad" -> FN_TORAD;
            case "rand" -> FN_RAND;
            case "randexcept" -> FN_RANDEXCEPT;
            default -> FN_UNKNOWN;
        };
    }

    /** 校验函数名与参数个数；合法时返回 null。arity 为 -1 表示可变参数。 */
    public static String validateFunction(String name, int argCount) {
        Integer arity = FUNCTION_ARITY.get(name);
        if (arity == null) return "Unknown function: " + name;
        if (arity >= 0 && arity != argCount) {
            return "Function '" + name + "' expects " + arity + " argument(s), got " + argCount;
        }
        return null;
    }

    public static Object eval(ExprNode node, int x, int z, int ly) {
        EvalContext context = CONTEXT.get();
        context.reset();
        return eval(node, x, z, ly, context);
    }

    private static Object eval(ExprNode node, int x, int z, int ly, EvalContext context) {
        return switch (node) {
            case ExprNode.NumberNode n -> n.value();
            case ExprNode.VariableNode v -> context.lookup(v.name(), x, z, ly);
            case ExprNode.BlockNode b -> resolveBlock(b);
            case ExprNode.BinaryNode b -> evalBinary(b, x, z, ly, context);
            case ExprNode.UnaryNode u -> evalUnary(u, x, z, ly, context);
            case ExprNode.ConditionalNode c -> evalConditional(c, x, z, ly, context);
            case ExprNode.FuncCallNode f -> evalFunc(f, x, z, ly, context);
            case ExprNode.BlockExprNode be -> evalBlockExpr(be, x, z, ly, context);
            // 编译后的形态：变量读取变成数组下标，不再有任何 Map 操作
            case ExprNode.BuiltinNode b -> builtinValue(b.kind(), x, z, ly);
            case ExprNode.SlotNode s -> context.slot(s.slot());
            case ExprNode.CompiledFuncCallNode f -> evalCompiledFunc(f, x, z, ly, context);
            case ExprNode.CompiledBlockNode cb -> evalCompiledBlock(cb, x, z, ly, context);
        };
    }

    private static Object evalCompiledBlock(ExprNode.CompiledBlockNode block, int x, int z, int ly,
                                            EvalContext context) {
        // 与 y 无关的绑定每列只需算一次：本节点在本列尚未预备时先补齐，
        // 之后逐格求值只剩与 y 相关的绑定与 body。
        if (!context.isPrepared(block.id(), x, z)) {
            prepareHoisted(block, x, z, ly, context);
        }
        int[] slots = block.slots();
        ExprNode[] values = block.values();
        boolean[] hoisted = block.hoisted();
        for (int i = 0; i < values.length; i++) {
            if (hoisted[i]) continue; // 已由 prepareHoisted 填入槽位
            context.setSlot(slots[i], eval(values[i], x, z, ly, context));
        }
        return eval(block.body(), x, z, ly, context);
    }

    /**
     * 按绑定顺序求值一个块里与 y 无关的绑定并写入槽位，然后标记本列已预备。
     *
     * <p>绑定值只会引用更早的绑定，而与 y 无关的值只能引用同样与 y 无关的槽位
     * （否则它自己就会与 y 相关），因此按顺序求值即可，无需拓扑排序。
     *
     * <p>正确性依赖两点：一是本节点的槽位只有本节点会读写；二是预备标记按
     * (节点 id, x, z) 记录，且槽位跨表达式全局唯一（见 {@code ExprCompiler}），
     * 别的表达式不会覆盖已预备的值。
     */
    private static void prepareHoisted(ExprNode.CompiledBlockNode block, int x, int z, int ly,
                                       EvalContext context) {
        int[] slots = block.slots();
        ExprNode[] values = block.values();
        boolean[] hoisted = block.hoisted();
        for (int i = 0; i < values.length; i++) {
            if (!hoisted[i]) continue;
            context.setSlot(slots[i], eval(values[i], x, z, ly, context));
        }
        context.markPrepared(block.id(), x, z);
    }

    private static double builtinValue(int kind, int x, int z, int ly) {
        return switch (kind) { case 0 -> x; case 1 -> z; case 2 -> ly; default -> 0; };
    }

    /** 方块字面量只解析一次；并发竞争时结果相同，最坏只是重复解析。 */
    private static BlockState resolveBlock(ExprNode.BlockNode node) {
        Object cached = node.resolved();
        if (cached != null) return (BlockState) cached;
        BlockState state = BlockResolver.resolve(node.blockId());
        node.resolved(state);
        return state;
    }

    /**
     * 判断表达式的结果是否随纵坐标变化。
     *
     * <p>表达式里只有 {@code ly} 能引用 y；{@code rand}/{@code randexcept} 也要算作
     * 与 y 相关，因为它们经 {@code pickIndex(x, z, ly, ...)} 隐式取用了 ly。
     * {@code let} 绑定可以遮蔽 {@code ly}，因此需要沿途跟踪已绑定的名字。
     *
     * <p>判定为 false 时，同一列内所有 y 的结果必然相同，调用方可以只求值一次。
     *
     * <p>本方法工作于未编译的 AST，用于「整层/整个条目是否与 y 无关」这类决策。编译后的
     * 节点由 {@code ExprCompiler} 在编译期逐个绑定判定，用于把单个绑定提升为每列一次。
     */
    public static boolean dependsOnLy(ExprNode node) {
        return dependsOnLy(node, Set.of());
    }

    private static boolean dependsOnLy(ExprNode node, Set<String> shadowed) {
        return switch (node) {
            case ExprNode.NumberNode n -> false;
            case ExprNode.BlockNode b -> false;
            case ExprNode.VariableNode v -> v.name().equals("ly") && !shadowed.contains("ly");
            case ExprNode.BinaryNode b -> dependsOnLy(b.left(), shadowed) || dependsOnLy(b.right(), shadowed);
            case ExprNode.UnaryNode u -> dependsOnLy(u.operand(), shadowed);
            case ExprNode.ConditionalNode c ->
                    dependsOnLy(c.condition(), shadowed)
                            || dependsOnLy(c.thenExpr(), shadowed)
                            || dependsOnLy(c.elseExpr(), shadowed);
            case ExprNode.FuncCallNode f -> {
                if (f.name().equals("rand") || f.name().equals("randexcept")) yield true;
                for (ExprNode arg : f.args()) {
                    if (dependsOnLy(arg, shadowed)) yield true;
                }
                yield false;
            }
            case ExprNode.BlockExprNode be -> {
                Set<String> inner = new HashSet<>(shadowed);
                for (ExprNode.LetBinding binding : be.bindings()) {
                    // 绑定值先于绑定名生效，因此顺序不能颠倒
                    if (dependsOnLy(binding.value(), inner)) yield true;
                    inner.add(binding.name());
                }
                yield dependsOnLy(be.body(), inner);
            }
            // 编译后的形态：槽位无法在此反查来源，保守视为与 y 相关。
            // 实际调用发生在编译之前（见 FormulaParser），因此不影响优化生效。
            case ExprNode.BuiltinNode b -> b.kind() == 2;
            case ExprNode.SlotNode s -> true;
            case ExprNode.CompiledFuncCallNode f -> true;
            case ExprNode.CompiledBlockNode cb -> true;
        };
    }

    private static Object evalBlockExpr(ExprNode.BlockExprNode block, int x, int z, int ly, EvalContext context) {
        context.enterScope();
        try {
            for (ExprNode.LetBinding binding : block.bindings()) {
                context.bind(binding.name(), eval(binding.value(), x, z, ly, context));
            }
            return eval(block.body(), x, z, ly, context);
        } finally {
            context.exitScope();
        }
    }

    private static double builtinValue(String name, int x, int z, int ly) {
        return switch (name) { case "x" -> x; case "z" -> z; case "ly" -> ly; default -> 0; };
    }

    private static Object evalBinary(ExprNode.BinaryNode b, int x, int z, int ly, EvalContext context) {
        BinaryOp op = b.op();
        // 逻辑与/或需要短路，相等比较要按运行时类型分派：这两类走通用路径
        if (op == BinaryOp.AND) return toBool(eval(b.left(), x, z, ly, context)) && toBool(eval(b.right(), x, z, ly, context));
        if (op == BinaryOp.OR) return toBool(eval(b.left(), x, z, ly, context)) || toBool(eval(b.right(), x, z, ly, context));
        if (op == BinaryOp.EQ || op == BinaryOp.NE) {
            return compEqNe(op, eval(b.left(), x, z, ly, context), eval(b.right(), x, z, ly, context));
        }
        double result = evalNumberBinary(b, x, z, ly, context);
        // 比较结果保持 Boolean，与改造前的返回类型一致
        return switch (op) {
            case LT, GT, LE, GE -> result != 0;
            default -> result;
        };
    }

    /**
     * 数值子树的原语求值路径。
     *
     * <p>{@link #eval} 统一返回 Object，于是每一步算术都要把结果装箱成 Double，
     * 而 Double 没有对象池——每次都是新对象。算术密集的公式一个区块可达数千万次
     * 分配。本路径让中间结果停留在 double，只在整个表达式的结果处装箱。
     *
     * <p>遇到非数值子树（方块、变量槽位等）时回退到通用路径，语义完全一致。
     */
    private static double evalNumber(ExprNode node, int x, int z, int ly, EvalContext context) {
        return switch (node) {
            case ExprNode.NumberNode n -> n.value();
            case ExprNode.BuiltinNode b -> builtinValue(b.kind(), x, z, ly);
            case ExprNode.SlotNode s -> toDouble(context.slot(s.slot()));
            case ExprNode.BinaryNode b -> evalNumberBinary(b, x, z, ly, context);
            case ExprNode.UnaryNode u -> u.op() == ExprNode.UnaryOp.NEG
                    ? -evalNumber(u.operand(), x, z, ly, context)
                    : (toBool(eval(u, x, z, ly, context)) ? 0d : 1d);
            case ExprNode.ConditionalNode c -> toBool(eval(c.condition(), x, z, ly, context))
                    ? evalNumber(c.thenExpr(), x, z, ly, context)
                    : evalNumber(c.elseExpr(), x, z, ly, context);
            case ExprNode.FuncCallNode f -> evalNumberFunc(f, x, z, ly, context);
            case ExprNode.CompiledFuncCallNode f -> evalCompiledNumberFunc(f, x, z, ly, context);
            default -> toDouble(eval(node, x, z, ly, context));
        };
    }

    private static double evalNumberBinary(ExprNode.BinaryNode b, int x, int z, int ly, EvalContext context) {
        BinaryOp op = b.op();
        if (op == BinaryOp.AND || op == BinaryOp.OR || op == BinaryOp.EQ || op == BinaryOp.NE) {
            // 需要短路或按类型分派，回退到通用路径
            return toDouble(evalBinary(b, x, z, ly, context));
        }
        double left = evalNumber(b.left(), x, z, ly, context);
        double right = evalNumber(b.right(), x, z, ly, context);
        return switch (op) {
            case ADD -> left + right;
            case SUB -> left - right;
            case MUL -> left * right;
            case DIV -> right == 0 ? 0 : left / right;
            case MOD -> right == 0 ? 0 : left % right;
            case LT -> left < right ? 1 : 0;
            case GT -> left > right ? 1 : 0;
            case LE -> left <= right ? 1 : 0;
            case GE -> left >= right ? 1 : 0;
            default -> 0;
        };
    }

    private static boolean compEqNe(BinaryOp op, Object left, Object right) {
        boolean eq;
        if (left instanceof BlockState ls && right instanceof BlockState rs) {
            eq = ls.getBlock() == rs.getBlock();
        } else if (left instanceof BlockState || right instanceof BlockState) {
            eq = false;
        } else if (left instanceof Boolean lb && right instanceof Boolean rb) {
            eq = lb == rb;
        } else {
            eq = Math.abs(toDouble(left) - toDouble(right)) < 1e-9;
        }
        return op == BinaryOp.EQ ? eq : !eq;
    }

    private static Object evalUnary(ExprNode.UnaryNode u, int x, int z, int ly, EvalContext context) {
        if (u.op() == ExprNode.UnaryOp.NEG) {
            // 取负走原语路径，中间的算术结果不必装箱
            return -evalNumber(u.operand(), x, z, ly, context);
        }
        return !toBool(eval(u.operand(), x, z, ly, context));
    }

    private static Object evalConditional(ExprNode.ConditionalNode c, int x, int z, int ly, EvalContext context) {
        return toBool(eval(c.condition(), x, z, ly, context))
                ? eval(c.thenExpr(), x, z, ly, context)
                : eval(c.elseExpr(), x, z, ly, context);
    }

    private static boolean isNumericFunction(String name) {
        return !name.equals("rand") && !name.equals("randexcept") && FUNCTION_ARITY.containsKey(name);
    }

    private static Object evalFunc(ExprNode.FuncCallNode f, int x, int z, int ly, EvalContext context) {
        // 数值函数统一走原语实现，仅在此处装箱一次
        if (isNumericFunction(f.name())) return evalNumberFunc(f, x, z, ly, context);
        List<ExprNode> args = f.args();
        return switch (f.name()) {
            // rand/randexcept 是变参且返回方块，不属于数值路径
            case "rand" -> evalRand(args, x, z, ly, context);
            case "randexcept" -> evalRandExcept(args, x, z, ly, context);
            default -> throw new IllegalArgumentException("Unknown function: " + f.name());
        };
    }

    /**
     * 数值函数的原语实现，供 {@link #evalNumber} 与 {@link #evalFunc} 共用。
     *
     * <p>定参函数直接取参数求值，既不为每次调用构造参数列表，也不为函数结果装箱。
     */
    private static double evalNumberFunc(ExprNode.FuncCallNode f, int x, int z, int ly, EvalContext context) {
        List<ExprNode> args = f.args();
        return switch (f.name()) {
            case "floordiv" -> { int a = (int) evalNumber(args.get(0), x, z, ly, context); int b = (int) evalNumber(args.get(1), x, z, ly, context); yield b == 0 ? 0 : Math.floorDiv(a, b); }
            case "floormod" -> { int a = (int) evalNumber(args.get(0), x, z, ly, context); int b = (int) evalNumber(args.get(1), x, z, ly, context); yield b == 0 ? 0 : Math.floorMod(a, b); }
            case "abs"   -> Math.abs(evalNumber(args.get(0), x, z, ly, context));
            case "max"   -> Math.max(evalNumber(args.get(0), x, z, ly, context), evalNumber(args.get(1), x, z, ly, context));
            case "min"   -> Math.min(evalNumber(args.get(0), x, z, ly, context), evalNumber(args.get(1), x, z, ly, context));
            case "floor" -> Math.floor(evalNumber(args.get(0), x, z, ly, context));
            case "ceil"  -> Math.ceil(evalNumber(args.get(0), x, z, ly, context));
            case "round" -> Math.round(evalNumber(args.get(0), x, z, ly, context));
            case "sign"  -> Math.signum(evalNumber(args.get(0), x, z, ly, context));
            case "sqrt"  -> Math.sqrt(evalNumber(args.get(0), x, z, ly, context));
            case "pow"   -> Math.pow(evalNumber(args.get(0), x, z, ly, context), evalNumber(args.get(1), x, z, ly, context));
            case "exp"   -> Math.exp(evalNumber(args.get(0), x, z, ly, context));
            case "log"   -> Math.log(evalNumber(args.get(0), x, z, ly, context));
            case "log10" -> Math.log10(evalNumber(args.get(0), x, z, ly, context));
            case "sin"   -> Math.sin(evalNumber(args.get(0), x, z, ly, context));
            case "cos"   -> Math.cos(evalNumber(args.get(0), x, z, ly, context));
            case "tan"   -> Math.tan(evalNumber(args.get(0), x, z, ly, context));
            case "asin"  -> Math.asin(evalNumber(args.get(0), x, z, ly, context));
            case "acos"  -> Math.acos(evalNumber(args.get(0), x, z, ly, context));
            case "atan"  -> Math.atan(evalNumber(args.get(0), x, z, ly, context));
            case "todeg" -> Math.toDegrees(evalNumber(args.get(0), x, z, ly, context));
            case "torad" -> Math.toRadians(evalNumber(args.get(0), x, z, ly, context));
            // 非数值函数（rand/randexcept）返回方块，按数值语境取 0
            default -> toDouble(evalFunc(f, x, z, ly, context));
        };
    }

    /**
     * 编译后的函数调用（通用路径）。
     *
     * <p>数值函数复用原语实现，只在这里装箱一次；rand/randexcept 返回方块，走原来的实现。
     */
    private static Object evalCompiledFunc(ExprNode.CompiledFuncCallNode f, int x, int z, int ly,
                                           EvalContext context) {
        if (f.id() == FN_RAND) return evalRand(f.args(), x, z, ly, context);
        if (f.id() == FN_RANDEXCEPT) return evalRandExcept(f.args(), x, z, ly, context);
        return evalCompiledNumberFunc(f, x, z, ly, context);
    }

    /**
     * 编译后的数值函数原语实现：按 int 编号分派，语义与 {@link #evalNumberFunc} 逐项对应。
     *
     * <p>函数编号由 {@code ExprCompiler} 在编译期写入，运行期不再有
     * {@code isNumericFunction} 的名字检查、{@code HashMap} 查找与字符串 switch。
     */
    private static double evalCompiledNumberFunc(ExprNode.CompiledFuncCallNode f, int x, int z, int ly,
                                                 EvalContext context) {
        List<ExprNode> args = f.args();
        return switch (f.id()) {
            case FN_FLOORDIV -> { int a = (int) evalNumber(args.get(0), x, z, ly, context); int b = (int) evalNumber(args.get(1), x, z, ly, context); yield b == 0 ? 0 : Math.floorDiv(a, b); }
            case FN_FLOORMOD -> { int a = (int) evalNumber(args.get(0), x, z, ly, context); int b = (int) evalNumber(args.get(1), x, z, ly, context); yield b == 0 ? 0 : Math.floorMod(a, b); }
            case FN_ABS   -> Math.abs(evalNumber(args.get(0), x, z, ly, context));
            case FN_MAX   -> Math.max(evalNumber(args.get(0), x, z, ly, context), evalNumber(args.get(1), x, z, ly, context));
            case FN_MIN   -> Math.min(evalNumber(args.get(0), x, z, ly, context), evalNumber(args.get(1), x, z, ly, context));
            case FN_FLOOR -> Math.floor(evalNumber(args.get(0), x, z, ly, context));
            case FN_CEIL  -> Math.ceil(evalNumber(args.get(0), x, z, ly, context));
            case FN_ROUND -> Math.round(evalNumber(args.get(0), x, z, ly, context));
            case FN_SIGN  -> Math.signum(evalNumber(args.get(0), x, z, ly, context));
            case FN_SQRT  -> Math.sqrt(evalNumber(args.get(0), x, z, ly, context));
            case FN_POW   -> Math.pow(evalNumber(args.get(0), x, z, ly, context), evalNumber(args.get(1), x, z, ly, context));
            case FN_EXP   -> Math.exp(evalNumber(args.get(0), x, z, ly, context));
            case FN_LOG   -> Math.log(evalNumber(args.get(0), x, z, ly, context));
            case FN_LOG10 -> Math.log10(evalNumber(args.get(0), x, z, ly, context));
            case FN_SIN   -> Math.sin(evalNumber(args.get(0), x, z, ly, context));
            case FN_COS   -> Math.cos(evalNumber(args.get(0), x, z, ly, context));
            case FN_TAN   -> Math.tan(evalNumber(args.get(0), x, z, ly, context));
            case FN_ASIN  -> Math.asin(evalNumber(args.get(0), x, z, ly, context));
            case FN_ACOS  -> Math.acos(evalNumber(args.get(0), x, z, ly, context));
            case FN_ATAN  -> Math.atan(evalNumber(args.get(0), x, z, ly, context));
            case FN_TODEG -> Math.toDegrees(evalNumber(args.get(0), x, z, ly, context));
            case FN_TORAD -> Math.toRadians(evalNumber(args.get(0), x, z, ly, context));
            // rand/randexcept 返回方块，按数值语境取 0（与未编译路径一致）
            case FN_RAND, FN_RANDEXCEPT -> toDouble(evalCompiledFunc(f, x, z, ly, context));
            default -> throw new IllegalArgumentException("Unknown compiled function id: " + f.id());
        };
    }

    private static int pickIndex(int x, int z, int y, int bound) {
        if (bound <= 0) return 0;
        int h = (x * 374761393) ^ (z * 668265263) ^ (y * 997307);
        h = h ^ (h >>> 16);
        return Math.floorMod(h & 0x7FFFFFFF, bound);
    }

    private static Object evalRand(List<ExprNode> args, int x, int z, int ly, EvalContext context) {
        if (args.isEmpty()) {
            List<BlockState> all = getAllBlocks();
            return all.isEmpty() ? BlockResolver.resolve("minecraft:air") : all.get(pickIndex(x, z, ly, all.size()));
        }
        List<BlockState> states = new ArrayList<>(args.size());
        for (ExprNode arg : args) {
            Object value = eval(arg, x, z, ly, context);
            if (value instanceof BlockState bs) states.add(bs);
        }
        if (states.isEmpty()) return BlockResolver.resolve("minecraft:air");
        return states.get(pickIndex(x, z, ly, states.size()));
    }

    private static final int RANDEXCEPT_CACHE_LIMIT = 64;
    private static final Map<List<BlockState>, List<BlockState>> RANDEXCEPT_CACHE = new LinkedHashMap<>(16, 0.75f, true);

    private static Object evalRandExcept(List<ExprNode> args, int x, int z, int ly, EvalContext context) {
        List<BlockState> excluded = new ArrayList<>();
        for (ExprNode arg : args) {
            Object value = eval(arg, x, z, ly, context);
            if (value instanceof BlockState bs) excluded.add(bs);
        }
        List<BlockState> key = List.copyOf(excluded);
        List<BlockState> pool;
        synchronized (RANDEXCEPT_CACHE) {
            pool = RANDEXCEPT_CACHE.get(key);
            if (pool == null && !RANDEXCEPT_CACHE.containsKey(key)) {
                List<BlockState> all = new ArrayList<>(getAllBlocks());
                all.removeAll(key);
                pool = Collections.unmodifiableList(all);
                RANDEXCEPT_CACHE.put(key, pool);
                if (RANDEXCEPT_CACHE.size() > RANDEXCEPT_CACHE_LIMIT) {
                    RANDEXCEPT_CACHE.remove(RANDEXCEPT_CACHE.keySet().iterator().next());
                }
            }
        }
        if (pool == null || pool.isEmpty()) return BlockResolver.resolve("minecraft:air");
        return pool.get(pickIndex(x, z, ly, pool.size()));
    }

    private static volatile List<BlockState> ALL_BLOCKS;
    private static List<BlockState> getAllBlocks() {
        List<BlockState> cached = ALL_BLOCKS;
        if (cached != null) return cached;
        List<BlockState> list = new ArrayList<>();
        for (Block block : BuiltInRegistries.BLOCK) {
            BlockState state = block.defaultBlockState();
            if (!state.isAir()) list.add(state);
        }
        cached = List.copyOf(list);
        ALL_BLOCKS = cached;
        return cached;
    }

    public static BlockState evalToBlock(ExprNode node, int x, int z, int ly) {
        Object result = eval(node, x, z, ly);
        return result instanceof BlockState bs ? bs : BlockResolver.resolve("minecraft:air");
    }

    private static double toDouble(Object o) { if (o instanceof Number n) return n.doubleValue(); if (o instanceof Boolean b) return b ? 1d : 0d; return 0d; }
    private static boolean toBool(Object o) { if (o instanceof Boolean b) return b; if (o instanceof Number n) return n.doubleValue() != 0; return false; }

    private static final class EvalContext {
        private static final Object MISSING = new Object();

        private final Map<String, Object> bindings = new HashMap<>();
        private final ArrayDeque<Scope> scopes = new ArrayDeque<>();
        /** 编译后形式的 let 绑定槽位；按需增长，跨次求值复用。 */
        private Object[] slots = new Object[16];

        // 编译块在本列是否已预备。列以 (x, z) 标识，块以节点 id 区分；
        // reset() 刻意不清空——提升的绑定正是要跨同一列的多次逐格求值复用。
        private boolean[] prepared = new boolean[8];
        private int[] preparedX = new int[8];
        private int[] preparedZ = new int[8];

        void reset() {
            bindings.clear();
            scopes.clear();
        }

        boolean isPrepared(int id, int x, int z) {
            return id < prepared.length && prepared[id] && preparedX[id] == x && preparedZ[id] == z;
        }

        void markPrepared(int id, int x, int z) {
            if (id >= prepared.length) {
                int grown = Math.max(id + 1, prepared.length * 2);
                prepared = Arrays.copyOf(prepared, grown);
                preparedX = Arrays.copyOf(preparedX, grown);
                preparedZ = Arrays.copyOf(preparedZ, grown);
            }
            prepared[id] = true;
            preparedX[id] = x;
            preparedZ[id] = z;
        }

        void setSlot(int slot, Object value) {
            Object[] current = slots;
            if (slot >= current.length) {
                current = Arrays.copyOf(current, Math.max(slot + 1, current.length * 2));
                slots = current;
            }
            current[slot] = value;
        }

        Object slot(int slot) {
            return slots[slot];
        }

        void enterScope() { scopes.push(new Scope()); }

        void bind(String name, Object value) {
            Scope scope = scopes.peek();
            if (scope == null) throw new IllegalStateException("let binding outside expression block");
            scope.previous.putIfAbsent(name, bindings.containsKey(name) ? bindings.get(name) : MISSING);
            bindings.put(name, value);
        }

        void exitScope() {
            Scope scope = scopes.pop();
            for (Map.Entry<String, Object> entry : scope.previous.entrySet()) {
                if (entry.getValue() == MISSING) bindings.remove(entry.getKey());
                else bindings.put(entry.getKey(), entry.getValue());
            }
        }

        Object lookup(String name, int x, int z, int ly) {
            return bindings.containsKey(name) ? bindings.get(name) : builtinValue(name, x, z, ly);
        }
    }

    private static final class Scope {
        private final Map<String, Object> previous = new HashMap<>();
    }
}
