package com.kncatl.ohmyworld.expr;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
            case ExprNode.BlockNode b -> com.kncatl.ohmyworld.expr.BlockResolver.resolve(b.blockId());
            case ExprNode.BinaryNode b -> evalBinary(b, x, z, ly, context);
            case ExprNode.UnaryNode u -> evalUnary(u, x, z, ly, context);
            case ExprNode.ConditionalNode c -> evalConditional(c, x, z, ly, context);
            case ExprNode.FuncCallNode f -> evalFunc(f, x, z, ly, context);
            case ExprNode.BlockExprNode be -> evalBlockExpr(be, x, z, ly, context);
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
        if (op == BinaryOp.AND) return toBool(eval(b.left(), x, z, ly, context)) && toBool(eval(b.right(), x, z, ly, context));
        if (op == BinaryOp.OR) return toBool(eval(b.left(), x, z, ly, context)) || toBool(eval(b.right(), x, z, ly, context));
        Object left = eval(b.left(), x, z, ly, context);
        Object right = eval(b.right(), x, z, ly, context);
        if (op == BinaryOp.EQ || op == BinaryOp.NE) return compEqNe(op, left, right);
        if (op == BinaryOp.LT || op == BinaryOp.GT || op == BinaryOp.LE || op == BinaryOp.GE) {
            double la = toDouble(left); double ra = toDouble(right);
            return switch (op) { case LT -> la < ra; case GT -> la > ra; case LE -> la <= ra; case GE -> la >= ra; default -> false; };
        }
        double la = toDouble(left); double ra = toDouble(right);
        return switch (op) {
            case ADD -> la + ra; case SUB -> la - ra; case MUL -> la * ra;
            case DIV -> (ra == 0 ? 0 : la / ra); case MOD -> (ra == 0 ? 0 : la % ra); default -> 0d;
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
        Object val = eval(u.operand(), x, z, ly, context);
        return switch (u.op()) { case NOT -> !toBool(val); case NEG -> -toDouble(val); };
    }

    private static Object evalConditional(ExprNode.ConditionalNode c, int x, int z, int ly, EvalContext context) {
        return toBool(eval(c.condition(), x, z, ly, context))
                ? eval(c.thenExpr(), x, z, ly, context)
                : eval(c.elseExpr(), x, z, ly, context);
    }

    private static Object evalFunc(ExprNode.FuncCallNode f, int x, int z, int ly, EvalContext context) {
        List<Object> raw = new ArrayList<>(f.args().size());
        for (ExprNode arg : f.args()) raw.add(eval(arg, x, z, ly, context));
        return switch (f.name()) {
            case "floordiv" -> { int a = toInt(raw.get(0)); int b = toInt(raw.get(1)); yield (double)(b == 0 ? 0 : Math.floorDiv(a, b)); }
            case "floormod" -> { int a = toInt(raw.get(0)); int b = toInt(raw.get(1)); yield (double)(b == 0 ? 0 : Math.floorMod(a, b)); }
            case "abs"   -> Math.abs(toDouble(raw.get(0))); case "max" -> Math.max(toDouble(raw.get(0)), toDouble(raw.get(1))); case "min" -> Math.min(toDouble(raw.get(0)), toDouble(raw.get(1)));
            case "floor" -> Math.floor(toDouble(raw.get(0))); case "ceil" -> Math.ceil(toDouble(raw.get(0))); case "round" -> (double)Math.round(toDouble(raw.get(0)));
            case "sign"  -> (double)Math.signum(toDouble(raw.get(0))); case "sqrt" -> Math.sqrt(toDouble(raw.get(0)));
            case "pow"   -> Math.pow(toDouble(raw.get(0)), toDouble(raw.get(1))); case "exp" -> Math.exp(toDouble(raw.get(0)));
            case "log"   -> Math.log(toDouble(raw.get(0))); case "log10" -> Math.log10(toDouble(raw.get(0)));
            case "sin"   -> Math.sin(toDouble(raw.get(0))); case "cos" -> Math.cos(toDouble(raw.get(0))); case "tan" -> Math.tan(toDouble(raw.get(0)));
            case "asin"  -> Math.asin(toDouble(raw.get(0))); case "acos" -> Math.acos(toDouble(raw.get(0))); case "atan" -> Math.atan(toDouble(raw.get(0)));
            case "todeg" -> Math.toDegrees(toDouble(raw.get(0))); case "torad" -> Math.toRadians(toDouble(raw.get(0)));
            case "rand" -> evalRand(raw, x, z, ly); case "randexcept" -> evalRandExcept(raw, x, z, ly);
            default -> throw new IllegalArgumentException("Unknown function: " + f.name());
        };
    }

    private static int pickIndex(int x, int z, int y, int bound) {
        if (bound <= 0) return 0;
        int h = (x * 374761393) ^ (z * 668265263) ^ (y * 997307);
        h = h ^ (h >>> 16);
        return Math.floorMod(h & 0x7FFFFFFF, bound);
    }

    private static Object evalRand(List<Object> raw, int x, int z, int ly) {
        if (raw.isEmpty()) {
            List<BlockState> all = getAllBlocks();
            return all.isEmpty() ? BlockResolver.resolve("minecraft:air") : all.get(pickIndex(x, z, ly, all.size()));
        }
        List<BlockState> states = new ArrayList<>();
        for (Object arg : raw) if (arg instanceof BlockState bs) states.add(bs);
        if (states.isEmpty()) return BlockResolver.resolve("minecraft:air");
        return states.get(pickIndex(x, z, ly, states.size()));
    }

    private static final int RANDEXCEPT_CACHE_LIMIT = 64;
    private static final Map<List<BlockState>, List<BlockState>> RANDEXCEPT_CACHE = new LinkedHashMap<>(16, 0.75f, true);

    private static Object evalRandExcept(List<Object> raw, int x, int z, int ly) {
        List<BlockState> excluded = new ArrayList<>();
        for (Object arg : raw) if (arg instanceof BlockState bs) excluded.add(bs);
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
    private static int toInt(Object o) { if (o instanceof Number n) return n.intValue(); if (o instanceof Boolean b) return b ? 1 : 0; return 0; }
    private static boolean toBool(Object o) { if (o instanceof Boolean b) return b; if (o instanceof Number n) return n.doubleValue() != 0; return false; }

    private static final class EvalContext {
        private static final Object MISSING = new Object();

        private final Map<String, Object> bindings = new HashMap<>();
        private final ArrayDeque<Scope> scopes = new ArrayDeque<>();

        void reset() {
            bindings.clear();
            scopes.clear();
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
