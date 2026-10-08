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
import java.util.function.Function;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import com.kncatl.ohmyworld.expr.ExprNode.BinaryOp;

public class ExprEvaluator {

    public enum ValueType { NUMBER, BOOLEAN, BLOCK, UNKNOWN }

    private static final ThreadLocal<EvalContext> CONTEXT = ThreadLocal.withInitial(EvalContext::new);

    /**
     * 群系求值模式：非 null 时，表达式里的字面量交给它解析（返回群系 Holder），
     * 不触碰方块注册表。由 {@link #evalToBiome} 设置/清除；嵌套的方块求值
     * （biome 行的地形查询）会由 {@link #evalToBlock} / {@link #evalBlockArg}
     * 临时摘除。
     */
    private static final ThreadLocal<Function<String, Object>> BIOME_RESOLVER = new ThreadLocal<>();

    /**
     * biome 行读取"公式地形"的只读视图：全部由公式层直接计算（不读已生成的方块），
     * 因此群系填充阶段（地形尚未写入区块）也可用。
     */
    public interface TerrainView {
        /** 该列表面方块的 y（含水面）；全空列返回世界最低 y - 1。 */
        int surfaceY(int x, int z);

        /** 表面方块是否指定方块（按方块种类比较）。 */
        boolean surfaceIs(int x, int z, BlockState target);

        /** 指定 y 处是否指定方块（按方块种类比较；越界为 false）。 */
        boolean blockIs(int x, int z, int y, BlockState target);
    }

    /** 当前求值的地形视图（仅 biome 行有值）；由 {@link #evalToBiome} 设置/清除。 */
    private static final ThreadLocal<TerrainView> TERRAIN_VIEW = new ThreadLocal<>();

    /**
     * biomeis 的运行期视图：判断指定位置的"已存储群系"是否某个群系。
     * 由区块填充/基座查询注入（读区块容器）；没有视图时 biomeis 恒为假
     * （例如编辑器预览、以及不经过区块的查询）。
     */
    public interface BiomeView {
        boolean isBiome(int x, int y, int z, String biomeId);
    }

    private static final ThreadLocal<BiomeView> BIOME_VIEW = new ThreadLocal<>();

    /** 当前 biomeis 视图；可能为 null。 */
    public static BiomeView biomeView() { return BIOME_VIEW.get(); }

    /** 设置/清除 biomeis 视图（null = 清除）；调用方自行保存旧值以便嵌套恢复。 */
    public static void setBiomeView(BiomeView view) {
        if (view == null) BIOME_VIEW.remove();
        else BIOME_VIEW.set(view);
    }

    /**
     * 当前世界的种子；由 {@code WorldLoadHandler} 在世界加载时设置（各维度同值）。
     * 公式里以内置变量 {@code seed} 读取，{@code seedhash(...)} 与全部噪声也以它为混合起点。
     */
    /** 设置世界种子；除世界加载外，单元测试也可直接调用。 */
    public static void setWorldSeed(long seed) { Noise.setWorldSeed(seed); }

    /**
     * 出生点坐标（出生区块中心）；由 {@code WorldLoadHandler} 在维度加载时设置。
     *
     * <p>取"出生区块中心"而不是最终重生点：原版创建世界时先把出生区块中心写进
     * 存档（作为搜索起点），随后扫描一个安全落点并改写——而搜索本身会生成出生点
     * 周边的区块，那些区块读到的正是中心值。用中心值可保证全图一致（新世界的
     * 首批区块与之后的所有区块用同一个值）；它与最终重生点通常相差不超过一个区块。
     */
    private static volatile int spawnX;
    private static volatile int spawnZ;

    /** 设置出生点（出生区块中心）；除世界加载外，单元测试也可直接调用。 */
    public static void setWorldSpawn(int x, int z) { spawnX = x; spawnZ = z; }

    private static final Map<String, Integer> FUNCTION_ARITY = Map.ofEntries(
            Map.entry("floordiv", 2), Map.entry("floormod", 2),
            Map.entry("abs", 1), Map.entry("max", -1), Map.entry("min", -1),
            Map.entry("floor", 1), Map.entry("ceil", 1), Map.entry("round", 1),
            Map.entry("sign", 1), Map.entry("sqrt", 1), Map.entry("pow", 2),
            Map.entry("exp", 1), Map.entry("log", 1), Map.entry("log10", 1),
            Map.entry("sin", 1), Map.entry("cos", 1), Map.entry("tan", 1),
            Map.entry("asin", 1), Map.entry("acos", 1), Map.entry("atan", 1),
            Map.entry("todeg", 1), Map.entry("torad", 1),
            Map.entry("seedhash", -1),
            Map.entry("rand", -1), Map.entry("randexcept", -1),
            // biome 行的地形查询（只能出现在 biome 行；参数位决定方块字面量语义）
            Map.entry("terrain", 2), Map.entry("surfis", 3), Map.entry("blockis", 4),
            // 方块层的群系查询（只能出现在方块层；末位是群系字面量）
            Map.entry("biomeis", 4),
            // 1.2.5 自然世界原语
            Map.entry("clamp", 3), Map.entry("lerp", 3), Map.entry("smoothstep", 1),
            Map.entry("map", 5),
            Map.entry("noise2", 4), Map.entry("noise3", 5),
            Map.entry("fbm2", -1), Map.entry("fbm3", 6),
            Map.entry("worley2", 4), Map.entry("worley3", 5),
            // 1.2.6：样条映射（分段线性 / Catmull-Rom）与水位助手
            Map.entry("spline", -1), Map.entry("cspline", -1),
            Map.entry("waterline", 3),
            // 1.2.6：worley 变体（F2 / 边缘线）
            Map.entry("worley2f2", 4), Map.entry("worley2edge", 4),
            // 1.3.0：数学助手（atan2 等）
            Map.entry("atan2", 2), Map.entry("fract", 1), Map.entry("step", 2),
            Map.entry("smootherstep", 1), Map.entry("tanh", 1), Map.entry("hypot", 2),
            Map.entry("bias", 2), Map.entry("gain", 2), Map.entry("saturate", 1),
            Map.entry("select", 3), Map.entry("terrace", 3),
            // 1.3.0：噪声变体（多倍频 / 山脊 / 圆丘 / 侵蚀）
            Map.entry("fbma2", -1), Map.entry("ridged2", 6), Map.entry("billow2", 6),
            Map.entry("fbm2e", 6),
            // 1.3.0：多返回函数（只能用于元组 let；返回组件数见 MULTI_RETURN_ARITY）
            Map.entry("warp2", 5), Map.entry("warp3", 6),
            Map.entry("noise2g", 4), Map.entry("worley2c", 4),
            // 1.3.0：循环 / 空间 / 距离助手（编译期或求值器实现）
            Map.entry("sum", 4), Map.entry("shift", 3),
            Map.entry("slope", 1), Map.entry("grad", 1), Map.entry("curv", 1),
            Map.entry("isodist", 1));

    /** 多返回函数名 → 返回组件数（1.3.0）。这些名字不能出现在普通表达式位置。 */
    private static final Map<String, Integer> MULTI_RETURN_ARITY = Map.of(
            "warp2", 2, "warp3", 3, "noise2g", 3, "worley2c", 5, "grad", 2);

    /** 是否是已知的多返回函数。 */
    public static boolean isMultiFunction(String name) {
        return MULTI_RETURN_ARITY.containsKey(name);
    }

    /** 多返回函数的返回组件数；不是多返回函数时返回 null。 */
    public static Integer multiReturnArity(String name) {
        return MULTI_RETURN_ARITY.get(name);
    }

    /** 是否是已知函数名（语法高亮与校验共用同一张表）。 */
    public static boolean isFunctionName(String name) {
        return FUNCTION_ARITY.containsKey(name);
    }

    /** 全部函数名（单一来源；供指南漂移测试等使用）。 */
    public static Set<String> functionNames() {
        return FUNCTION_ARITY.keySet();
    }

    // 编译后的函数编号。ExprCompiler 在编译期把函数名解析成这些常量，
    // 运行期因此不再对函数名做字符串比较与哈希查找（每次调用都可观）。
    public static final int FN_FLOORDIV = 0, FN_FLOORMOD = 1, FN_ABS = 2, FN_MAX = 3, FN_MIN = 4,
            FN_FLOOR = 5, FN_CEIL = 6, FN_ROUND = 7, FN_SIGN = 8, FN_SQRT = 9, FN_POW = 10,
            FN_EXP = 11, FN_LOG = 12, FN_LOG10 = 13, FN_SIN = 14, FN_COS = 15, FN_TAN = 16,
            FN_ASIN = 17, FN_ACOS = 18, FN_ATAN = 19, FN_TODEG = 20, FN_TORAD = 21;
    public static final int FN_RAND = 100, FN_RANDEXCEPT = 101, FN_SEEDHASH = 102;
    public static final int FN_TERRAIN = 103, FN_SURFIS = 104, FN_BLOCKIS = 105;
    public static final int FN_BIOMEIS = 106;
    public static final int FN_CLAMP = 107, FN_LERP = 108, FN_SMOOTHSTEP = 109, FN_MAP = 110;
    public static final int FN_NOISE2 = 111, FN_NOISE3 = 112, FN_FBM2 = 113, FN_FBM3 = 114;
    public static final int FN_WORLEY2 = 115, FN_WORLEY3 = 116;
    public static final int FN_SPLINE = 117, FN_CSPLINE = 118, FN_WATERLINE = 119;
    public static final int FN_WORLEY2F2 = 120, FN_WORLEY2EDGE = 121;
    public static final int FN_ATAN2 = 122, FN_FRACT = 123, FN_STEP = 124,
            FN_SMOOTHERSTEP = 125, FN_TANH = 126, FN_HYPOT = 127,
            FN_BIAS = 128, FN_GAIN = 129, FN_SATURATE = 130, FN_SELECT = 131,
            FN_TERRACE = 132;
    public static final int FN_FBMA2 = 133, FN_RIDGED2 = 134, FN_BILLOW2 = 135, FN_FBM2E = 136;
    public static final int FN_WARP2 = 140, FN_WARP3 = 141, FN_NOISE2G = 142, FN_WORLEY2C = 143;
    public static final int FN_SLOPE = 145, FN_GRAD = 146, FN_CURV = 147, FN_ISODIST = 148;
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
            case "seedhash" -> FN_SEEDHASH;
            case "rand" -> FN_RAND;
            case "randexcept" -> FN_RANDEXCEPT;
            case "terrain" -> FN_TERRAIN;
            case "surfis" -> FN_SURFIS;
            case "blockis" -> FN_BLOCKIS;
            case "biomeis" -> FN_BIOMEIS;
            case "clamp" -> FN_CLAMP;
            case "lerp" -> FN_LERP;
            case "smoothstep" -> FN_SMOOTHSTEP;
            case "map" -> FN_MAP;
            case "noise2" -> FN_NOISE2;
            case "noise3" -> FN_NOISE3;
            case "fbm2" -> FN_FBM2;
            case "fbm3" -> FN_FBM3;
            case "worley2" -> FN_WORLEY2;
            case "worley3" -> FN_WORLEY3;
            case "spline" -> FN_SPLINE;
            case "cspline" -> FN_CSPLINE;
            case "waterline" -> FN_WATERLINE;
            case "worley2f2" -> FN_WORLEY2F2;
            case "worley2edge" -> FN_WORLEY2EDGE;
            case "atan2" -> FN_ATAN2;
            case "fract" -> FN_FRACT;
            case "step" -> FN_STEP;
            case "smootherstep" -> FN_SMOOTHERSTEP;
            case "tanh" -> FN_TANH;
            case "hypot" -> FN_HYPOT;
            case "bias" -> FN_BIAS;
            case "gain" -> FN_GAIN;
            case "saturate" -> FN_SATURATE;
            case "select" -> FN_SELECT;
            case "terrace" -> FN_TERRACE;
            case "fbma2" -> FN_FBMA2;
            case "ridged2" -> FN_RIDGED2;
            case "billow2" -> FN_BILLOW2;
            case "fbm2e" -> FN_FBM2E;
            case "slope" -> FN_SLOPE;
            case "curv" -> FN_CURV;
            case "isodist" -> FN_ISODIST;
            default -> FN_UNKNOWN;
        };
    }

    /** 编译期把多返回函数名解析成编号；未知返回 {@link #FN_UNKNOWN}。 */
    public static int tupleFunctionId(String name) {
        return switch (name) {
            case "warp2" -> FN_WARP2;
            case "warp3" -> FN_WARP3;
            case "noise2g" -> FN_NOISE2G;
            case "worley2c" -> FN_WORLEY2C;
            case "grad" -> FN_GRAD;
            default -> FN_UNKNOWN;
        };
    }

    /** 校验函数名与参数个数；合法时返回 null。arity 为 -1 表示可变参数。 */
    public static String validateFunction(String name, int argCount) {
        Integer arity = FUNCTION_ARITY.get(name);
        if (arity == null) return "Unknown function: " + name;
        if (name.equals("min") || name.equals("max")) {
            if (argCount < 2) return "Function '" + name + "' expects at least 2 arguments, got " + argCount;
            return null;
        }
        if (name.equals("fbm2")) {
            if (argCount != 5 && argCount != 7) {
                return "Function 'fbm2' expects 5 or 7 arguments, got " + argCount;
            }
            return null;
        }
        if (name.equals("fbma2")) {
            if (argCount < 5 || argCount > 12) {
                return "Function 'fbma2' expects 5 to 12 arguments (x, z, scale, salt, amplitudes...), got " + argCount;
            }
            return null;
        }
        if (name.equals("spline") || name.equals("cspline")) {
            // 值 + 至少两组"位置 值"点对：参数个数必须是 ≥5 的奇数
            if (argCount < 5 || argCount % 2 == 0) {
                return "Function '" + name + "' expects an odd number of arguments (value plus at least two point pairs), got " + argCount;
            }
            return null;
        }
        if (arity >= 0 && arity != argCount) {
            return "Function '" + name + "' expects " + arity + " argument(s), got " + argCount;
        }
        return null;
    }

    public static Object eval(ExprNode node, int x, int z, int ly) {
        return evalAt(node, x, z, ly, ly);
    }

    /**
     * 带绝对 y 的求值入口（内置变量 {@code y} 用）。没有层上下文时用
     * {@link #eval(ExprNode, int, int, int)}（那里 y 等于 ly）。
     */
    public static Object evalAt(ExprNode node, int x, int z, int ly, int globalY) {
        EvalContext context = CONTEXT.get();
        context.reset();
        context.globalY = globalY;
        return eval(node, x, z, ly, context);
    }

    private static Object eval(ExprNode node, int x, int z, int ly, EvalContext context) {
        return switch (node) {
            case ExprNode.NumberNode n -> n.value();
            case ExprNode.VariableNode v -> context.lookup(v.name(), x, z, ly);
            case ExprNode.BlockNode b -> {
                Function<String, Object> biomeResolver = BIOME_RESOLVER.get();
                yield biomeResolver != null ? biomeResolver.apply(b.blockId()) : resolveBlock(b);
            }
            case ExprNode.BinaryNode b -> evalBinary(b, x, z, ly, context);
            case ExprNode.UnaryNode u -> evalUnary(u, x, z, ly, context);
            case ExprNode.ConditionalNode c -> evalConditional(c, x, z, ly, context);
            case ExprNode.FuncCallNode f -> evalFunc(f, x, z, ly, context);
            case ExprNode.BlockExprNode be -> evalBlockExpr(be, x, z, ly, context);
            case ExprNode.TupleCallNode t -> evalTupleCall(t.name(), t.args(), x, z, ly, context);
            // 编译后的形态：变量读取变成数组下标，不再有任何 Map 操作
            case ExprNode.BuiltinNode b -> builtinValue(b.kind(), x, z, ly, context.globalY);
            case ExprNode.SlotNode s -> context.slot(s.slot());
            case ExprNode.CompiledFuncCallNode f -> evalCompiledFunc(f, x, z, ly, context);
            case ExprNode.CompiledTupleCallNode t -> evalCompiledTupleCall(t.id(), t.args(), x, z, ly, context);
            case ExprNode.CompiledBlockNode cb -> evalCompiledBlock(cb, x, z, ly, context);
            case ExprNode.TupleComponentNode t -> context.tupleComponent(t.slot(), t.index());
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

    private static double builtinValue(int kind, int x, int z, int ly, int globalY) {
        return switch (kind) {
            case 0 -> x;
            case 1 -> z;
            case 2 -> ly;
            case 3 -> (double) Noise.worldSeed;
            case 4 -> globalY;
            case 5 -> spawnX;
            case 6 -> spawnZ;
            default -> 0;
        };
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
            case ExprNode.VariableNode v ->
                    (v.name().equals("ly") || v.name().equals("y")) && !shadowed.contains(v.name());
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
                    inner.addAll(binding.names());
                }
                yield dependsOnLy(be.body(), inner);
            }
            case ExprNode.TupleCallNode t -> {
                for (ExprNode arg : t.args()) {
                    if (dependsOnLy(arg, shadowed)) yield true;
                }
                yield false;
            }
            case ExprNode.TupleComponentNode t -> t.tupleDependent();
            // 编译后的形态：槽位无法在此反查来源，保守视为与 y 相关。
            // 实际调用发生在编译之前（见 FormulaParser），因此不影响优化生效。
            case ExprNode.BuiltinNode b -> b.kind() == 2;
            case ExprNode.SlotNode s -> true;
            case ExprNode.CompiledFuncCallNode f -> true;
            case ExprNode.CompiledTupleCallNode t -> true;
            case ExprNode.CompiledBlockNode cb -> true;
        };
    }

    private static Object evalBlockExpr(ExprNode.BlockExprNode block, int x, int z, int ly, EvalContext context) {
        context.enterScope();
        try {
            for (ExprNode.LetBinding binding : block.bindings()) {
                if (binding.names().size() == 1) {
                    context.bind(binding.names().get(0), eval(binding.value(), x, z, ly, context));
                } else {
                    Object value = eval(binding.value(), x, z, ly, context);
                    double[] tuple = value instanceof double[] values ? values : new double[0];
                    for (int i = 0; i < binding.names().size(); i++) {
                        context.bind(binding.names().get(i), i < tuple.length ? tuple[i] : 0d);
                    }
                }
            }
            return eval(block.body(), x, z, ly, context);
        } finally {
            context.exitScope();
        }
    }

    private static double builtinValue(String name, int x, int z, int ly, int globalY) {
        return switch (name) {
            case "x" -> x;
            case "z" -> z;
            case "ly" -> ly;
            case "y" -> globalY;
            case "seed" -> (double) Noise.worldSeed;
            case "spawnx" -> spawnX;
            case "spawnz" -> spawnZ;
            default -> 0;
        };
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
            case ExprNode.BuiltinNode b -> builtinValue(b.kind(), x, z, ly, context.globalY);
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
            case ExprNode.TupleComponentNode t -> context.tupleComponent(t.slot(), t.index());
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
            case "max"   -> foldMinMax(args, true, x, z, ly, context);
            case "min"   -> foldMinMax(args, false, x, z, ly, context);
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
            case "seedhash" -> seedhash(args, x, z, ly, context);
            // 1.2.5 自然世界原语
            case "clamp" -> clamp(evalNumber(args.get(0), x, z, ly, context),
                    evalNumber(args.get(1), x, z, ly, context), evalNumber(args.get(2), x, z, ly, context));
            case "lerp" -> lerp(evalNumber(args.get(0), x, z, ly, context),
                    evalNumber(args.get(1), x, z, ly, context), evalNumber(args.get(2), x, z, ly, context));
            case "smoothstep" -> smoothstep(evalNumber(args.get(0), x, z, ly, context));
            case "map" -> mapValue(evalNumber(args.get(0), x, z, ly, context),
                    evalNumber(args.get(1), x, z, ly, context), evalNumber(args.get(2), x, z, ly, context),
                    evalNumber(args.get(3), x, z, ly, context), evalNumber(args.get(4), x, z, ly, context));
            case "noise2" -> Noise.noise2(evalNumber(args.get(0), x, z, ly, context),
                    evalNumber(args.get(1), x, z, ly, context), evalNumber(args.get(2), x, z, ly, context),
                    evalNumber(args.get(3), x, z, ly, context));
            case "noise3" -> Noise.noise3(evalNumber(args.get(0), x, z, ly, context),
                    evalNumber(args.get(1), x, z, ly, context), evalNumber(args.get(2), x, z, ly, context),
                    evalNumber(args.get(3), x, z, ly, context), evalNumber(args.get(4), x, z, ly, context));
            case "fbm2" -> evalFbm2(args, x, z, ly, context);
            case "fbm3" -> Noise.fbm3(evalNumber(args.get(0), x, z, ly, context),
                    evalNumber(args.get(1), x, z, ly, context), evalNumber(args.get(2), x, z, ly, context),
                    evalNumber(args.get(3), x, z, ly, context), evalNumber(args.get(4), x, z, ly, context),
                    evalNumber(args.get(5), x, z, ly, context));
            case "worley2" -> Noise.worley2(evalNumber(args.get(0), x, z, ly, context),
                    evalNumber(args.get(1), x, z, ly, context), evalNumber(args.get(2), x, z, ly, context),
                    evalNumber(args.get(3), x, z, ly, context));
            case "worley3" -> Noise.worley3(evalNumber(args.get(0), x, z, ly, context),
                    evalNumber(args.get(1), x, z, ly, context), evalNumber(args.get(2), x, z, ly, context),
                    evalNumber(args.get(3), x, z, ly, context), evalNumber(args.get(4), x, z, ly, context));
            case "spline" -> splineLinear(args, x, z, ly, context);
            case "cspline" -> splineCatmullRom(args, x, z, ly, context);
            case "waterline" -> waterline(args, x, z, ly, context);
            case "worley2f2" -> Noise.worley2f2(evalNumber(args.get(0), x, z, ly, context),
                    evalNumber(args.get(1), x, z, ly, context), evalNumber(args.get(2), x, z, ly, context),
                    evalNumber(args.get(3), x, z, ly, context));
            case "worley2edge" -> Noise.worley2edge(evalNumber(args.get(0), x, z, ly, context),
                    evalNumber(args.get(1), x, z, ly, context), evalNumber(args.get(2), x, z, ly, context),
                    evalNumber(args.get(3), x, z, ly, context));
            case "atan2" -> atan2(evalNumber(args.get(0), x, z, ly, context),
                    evalNumber(args.get(1), x, z, ly, context));
            case "fract" -> fract(evalNumber(args.get(0), x, z, ly, context));
            case "step" -> stepValue(evalNumber(args.get(0), x, z, ly, context),
                    evalNumber(args.get(1), x, z, ly, context));
            case "smootherstep" -> smootherstep(evalNumber(args.get(0), x, z, ly, context));
            case "tanh" -> Math.tanh(evalNumber(args.get(0), x, z, ly, context));
            case "hypot" -> hypot(evalNumber(args.get(0), x, z, ly, context),
                    evalNumber(args.get(1), x, z, ly, context));
            case "bias" -> bias(evalNumber(args.get(0), x, z, ly, context),
                    evalNumber(args.get(1), x, z, ly, context));
            case "gain" -> gain(evalNumber(args.get(0), x, z, ly, context),
                    evalNumber(args.get(1), x, z, ly, context));
            case "saturate" -> saturate(evalNumber(args.get(0), x, z, ly, context));
            case "select" -> evalNumber(args.get(0), x, z, ly, context) != 0
                    ? evalNumber(args.get(1), x, z, ly, context)
                    : evalNumber(args.get(2), x, z, ly, context);
            case "terrace" -> terrace(evalNumber(args.get(0), x, z, ly, context),
                    evalNumber(args.get(1), x, z, ly, context), evalNumber(args.get(2), x, z, ly, context));
            case "fbma2" -> Noise.fbma2(evalNumber(args.get(0), x, z, ly, context),
                    evalNumber(args.get(1), x, z, ly, context), evalNumber(args.get(2), x, z, ly, context),
                    evalNumber(args.get(3), x, z, ly, context), fbmaAmplitudes(args, x, z, ly, context));
            case "ridged2" -> Noise.ridged2(evalNumber(args.get(0), x, z, ly, context),
                    evalNumber(args.get(1), x, z, ly, context), evalNumber(args.get(2), x, z, ly, context),
                    evalNumber(args.get(3), x, z, ly, context), evalNumber(args.get(4), x, z, ly, context),
                    evalNumber(args.get(5), x, z, ly, context));
            case "billow2" -> Noise.billow2(evalNumber(args.get(0), x, z, ly, context),
                    evalNumber(args.get(1), x, z, ly, context), evalNumber(args.get(2), x, z, ly, context),
                    evalNumber(args.get(3), x, z, ly, context), evalNumber(args.get(4), x, z, ly, context),
                    evalNumber(args.get(5), x, z, ly, context));
            case "fbm2e" -> Noise.fbm2e(evalNumber(args.get(0), x, z, ly, context),
                    evalNumber(args.get(1), x, z, ly, context), evalNumber(args.get(2), x, z, ly, context),
                    evalNumber(args.get(3), x, z, ly, context), evalNumber(args.get(4), x, z, ly, context),
                    evalNumber(args.get(5), x, z, ly, context));
            case "slope" -> slopeOf(args, x, z, ly, context);
            case "curv" -> curvOf(args, x, z, ly, context);
            case "isodist" -> isodistOf(args, x, z, ly, context);
            // biome 行的地形查询（只会在 biome 求值环境里被调用）
            case "terrain" -> terrainHeight(args, x, z, ly, context);
            case "surfis" -> surfaceIsAt(args, x, z, ly, context) ? 1 : 0;
            case "blockis" -> blockIsAt(args, x, z, ly, context) ? 1 : 0;
            // 方块层的群系查询（只在区块填充/基座查询的视图下才有值）
            case "biomeis" -> biomeIs(args, x, z, ly, context) ? 1 : 0;
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
            case FN_MAX   -> foldMinMax(args, true, x, z, ly, context);
            case FN_MIN   -> foldMinMax(args, false, x, z, ly, context);
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
            case FN_SEEDHASH -> seedhash(args, x, z, ly, context);
            case FN_TERRAIN -> terrainHeight(args, x, z, ly, context);
            case FN_SURFIS -> surfaceIsAt(args, x, z, ly, context) ? 1 : 0;
            case FN_BLOCKIS -> blockIsAt(args, x, z, ly, context) ? 1 : 0;
            case FN_BIOMEIS -> biomeIs(args, x, z, ly, context) ? 1 : 0;
            case FN_CLAMP -> clamp(evalNumber(args.get(0), x, z, ly, context),
                    evalNumber(args.get(1), x, z, ly, context), evalNumber(args.get(2), x, z, ly, context));
            case FN_LERP -> lerp(evalNumber(args.get(0), x, z, ly, context),
                    evalNumber(args.get(1), x, z, ly, context), evalNumber(args.get(2), x, z, ly, context));
            case FN_SMOOTHSTEP -> smoothstep(evalNumber(args.get(0), x, z, ly, context));
            case FN_MAP -> mapValue(evalNumber(args.get(0), x, z, ly, context),
                    evalNumber(args.get(1), x, z, ly, context), evalNumber(args.get(2), x, z, ly, context),
                    evalNumber(args.get(3), x, z, ly, context), evalNumber(args.get(4), x, z, ly, context));
            case FN_NOISE2 -> Noise.noise2(evalNumber(args.get(0), x, z, ly, context),
                    evalNumber(args.get(1), x, z, ly, context), evalNumber(args.get(2), x, z, ly, context),
                    evalNumber(args.get(3), x, z, ly, context));
            case FN_NOISE3 -> Noise.noise3(evalNumber(args.get(0), x, z, ly, context),
                    evalNumber(args.get(1), x, z, ly, context), evalNumber(args.get(2), x, z, ly, context),
                    evalNumber(args.get(3), x, z, ly, context), evalNumber(args.get(4), x, z, ly, context));
            case FN_FBM2 -> evalFbm2(args, x, z, ly, context);
            case FN_FBM3 -> Noise.fbm3(evalNumber(args.get(0), x, z, ly, context),
                    evalNumber(args.get(1), x, z, ly, context), evalNumber(args.get(2), x, z, ly, context),
                    evalNumber(args.get(3), x, z, ly, context), evalNumber(args.get(4), x, z, ly, context),
                    evalNumber(args.get(5), x, z, ly, context));
            case FN_WORLEY2 -> Noise.worley2(evalNumber(args.get(0), x, z, ly, context),
                    evalNumber(args.get(1), x, z, ly, context), evalNumber(args.get(2), x, z, ly, context),
                    evalNumber(args.get(3), x, z, ly, context));
            case FN_WORLEY3 -> Noise.worley3(evalNumber(args.get(0), x, z, ly, context),
                    evalNumber(args.get(1), x, z, ly, context), evalNumber(args.get(2), x, z, ly, context),
                    evalNumber(args.get(3), x, z, ly, context), evalNumber(args.get(4), x, z, ly, context));
            case FN_SPLINE -> splineLinear(args, x, z, ly, context);
            case FN_CSPLINE -> splineCatmullRom(args, x, z, ly, context);
            case FN_WATERLINE -> waterline(args, x, z, ly, context);
            case FN_WORLEY2F2 -> Noise.worley2f2(evalNumber(args.get(0), x, z, ly, context),
                    evalNumber(args.get(1), x, z, ly, context), evalNumber(args.get(2), x, z, ly, context),
                    evalNumber(args.get(3), x, z, ly, context));
            case FN_WORLEY2EDGE -> Noise.worley2edge(evalNumber(args.get(0), x, z, ly, context),
                    evalNumber(args.get(1), x, z, ly, context), evalNumber(args.get(2), x, z, ly, context),
                    evalNumber(args.get(3), x, z, ly, context));
            case FN_ATAN2 -> atan2(evalNumber(args.get(0), x, z, ly, context),
                    evalNumber(args.get(1), x, z, ly, context));
            case FN_FRACT -> fract(evalNumber(args.get(0), x, z, ly, context));
            case FN_STEP -> stepValue(evalNumber(args.get(0), x, z, ly, context),
                    evalNumber(args.get(1), x, z, ly, context));
            case FN_SMOOTHERSTEP -> smootherstep(evalNumber(args.get(0), x, z, ly, context));
            case FN_TANH -> Math.tanh(evalNumber(args.get(0), x, z, ly, context));
            case FN_HYPOT -> hypot(evalNumber(args.get(0), x, z, ly, context),
                    evalNumber(args.get(1), x, z, ly, context));
            case FN_BIAS -> bias(evalNumber(args.get(0), x, z, ly, context),
                    evalNumber(args.get(1), x, z, ly, context));
            case FN_GAIN -> gain(evalNumber(args.get(0), x, z, ly, context),
                    evalNumber(args.get(1), x, z, ly, context));
            case FN_SATURATE -> saturate(evalNumber(args.get(0), x, z, ly, context));
            case FN_SELECT -> evalNumber(args.get(0), x, z, ly, context) != 0
                    ? evalNumber(args.get(1), x, z, ly, context)
                    : evalNumber(args.get(2), x, z, ly, context);
            case FN_TERRACE -> terrace(evalNumber(args.get(0), x, z, ly, context),
                    evalNumber(args.get(1), x, z, ly, context), evalNumber(args.get(2), x, z, ly, context));
            case FN_FBMA2 -> Noise.fbma2(evalNumber(args.get(0), x, z, ly, context),
                    evalNumber(args.get(1), x, z, ly, context), evalNumber(args.get(2), x, z, ly, context),
                    evalNumber(args.get(3), x, z, ly, context), fbmaAmplitudes(args, x, z, ly, context));
            case FN_RIDGED2 -> Noise.ridged2(evalNumber(args.get(0), x, z, ly, context),
                    evalNumber(args.get(1), x, z, ly, context), evalNumber(args.get(2), x, z, ly, context),
                    evalNumber(args.get(3), x, z, ly, context), evalNumber(args.get(4), x, z, ly, context),
                    evalNumber(args.get(5), x, z, ly, context));
            case FN_BILLOW2 -> Noise.billow2(evalNumber(args.get(0), x, z, ly, context),
                    evalNumber(args.get(1), x, z, ly, context), evalNumber(args.get(2), x, z, ly, context),
                    evalNumber(args.get(3), x, z, ly, context), evalNumber(args.get(4), x, z, ly, context),
                    evalNumber(args.get(5), x, z, ly, context));
            case FN_FBM2E -> Noise.fbm2e(evalNumber(args.get(0), x, z, ly, context),
                    evalNumber(args.get(1), x, z, ly, context), evalNumber(args.get(2), x, z, ly, context),
                    evalNumber(args.get(3), x, z, ly, context), evalNumber(args.get(4), x, z, ly, context),
                    evalNumber(args.get(5), x, z, ly, context));
            case FN_SLOPE -> slopeOf(args, x, z, ly, context);
            case FN_CURV -> curvOf(args, x, z, ly, context);
            case FN_ISODIST -> isodistOf(args, x, z, ly, context);
            // rand/randexcept 返回方块，按数值语境取 0（与未编译路径一致）
            case FN_RAND, FN_RANDEXCEPT -> toDouble(evalCompiledFunc(f, x, z, ly, context));
            default -> throw new IllegalArgumentException("Unknown compiled function id: " + f.id());
        };
    }

    // ---------------------------------------------------------- 多返回函数（1.3.0）

    /** 未编译的元组分派（只会在元组 let 的右侧被调用；语义校验保证函数名合法）。 */
    private static Object evalTupleCall(String name, List<ExprNode> args, int x, int z, int ly, EvalContext context) {
        return switch (name) {
            case "warp2" -> Noise.warp2(evalNumber(args.get(0), x, z, ly, context),
                    evalNumber(args.get(1), x, z, ly, context), evalNumber(args.get(2), x, z, ly, context),
                    evalNumber(args.get(3), x, z, ly, context), evalNumber(args.get(4), x, z, ly, context));
            case "warp3" -> Noise.warp3(evalNumber(args.get(0), x, z, ly, context),
                    evalNumber(args.get(1), x, z, ly, context), evalNumber(args.get(2), x, z, ly, context),
                    evalNumber(args.get(3), x, z, ly, context), evalNumber(args.get(4), x, z, ly, context),
                    evalNumber(args.get(5), x, z, ly, context));
            case "noise2g" -> Noise.noise2Grad(evalNumber(args.get(0), x, z, ly, context),
                    evalNumber(args.get(1), x, z, ly, context), evalNumber(args.get(2), x, z, ly, context),
                    evalNumber(args.get(3), x, z, ly, context));
            case "worley2c" -> Noise.worley2c(evalNumber(args.get(0), x, z, ly, context),
                    evalNumber(args.get(1), x, z, ly, context), evalNumber(args.get(2), x, z, ly, context),
                    evalNumber(args.get(3), x, z, ly, context));
            case "grad" -> gradient(args.get(0), x, z, ly, context);
            default -> throw new IllegalArgumentException("Unknown multi-return function: " + name);
        };
    }

    /** 编译后的元组分派：按 int 编号，语义与未编译路径逐项对应。 */
    private static Object evalCompiledTupleCall(int id, List<ExprNode> args, int x, int z, int ly,
                                                EvalContext context) {
        return switch (id) {
            case FN_WARP2 -> Noise.warp2(evalNumber(args.get(0), x, z, ly, context),
                    evalNumber(args.get(1), x, z, ly, context), evalNumber(args.get(2), x, z, ly, context),
                    evalNumber(args.get(3), x, z, ly, context), evalNumber(args.get(4), x, z, ly, context));
            case FN_WARP3 -> Noise.warp3(evalNumber(args.get(0), x, z, ly, context),
                    evalNumber(args.get(1), x, z, ly, context), evalNumber(args.get(2), x, z, ly, context),
                    evalNumber(args.get(3), x, z, ly, context), evalNumber(args.get(4), x, z, ly, context),
                    evalNumber(args.get(5), x, z, ly, context));
            case FN_NOISE2G -> Noise.noise2Grad(evalNumber(args.get(0), x, z, ly, context),
                    evalNumber(args.get(1), x, z, ly, context), evalNumber(args.get(2), x, z, ly, context),
                    evalNumber(args.get(3), x, z, ly, context));
            case FN_WORLEY2C -> Noise.worley2c(evalNumber(args.get(0), x, z, ly, context),
                    evalNumber(args.get(1), x, z, ly, context), evalNumber(args.get(2), x, z, ly, context),
                    evalNumber(args.get(3), x, z, ly, context));
            case FN_GRAD -> gradient(args.get(0), x, z, ly, context);
            default -> throw new IllegalArgumentException("Unknown compiled tuple function id: " + id);
        };
    }

    // ---------------------------------------------------------- biome 行的地形查询

    /** {@code terrain(x, z)}：公式地形在该列的表面方块 y（含水面；全空列为世界最低 y - 1）。 */
    private static double terrainHeight(List<ExprNode> args, int x, int z, int ly, EvalContext context) {
        TerrainView view = TERRAIN_VIEW.get();
        if (view == null) return 0d;
        return view.surfaceY(blockCoord(args.get(0), x, z, ly, context),
                blockCoord(args.get(1), x, z, ly, context));
    }

    /** {@code surfis(x, z, 方块)}：表面方块是否指定方块。 */
    private static boolean surfaceIsAt(List<ExprNode> args, int x, int z, int ly, EvalContext context) {
        TerrainView view = TERRAIN_VIEW.get();
        if (view == null) return false;
        BlockState target = blockValue(args.get(2), x, z, ly, context);
        if (target == null) return false;
        return view.surfaceIs(blockCoord(args.get(0), x, z, ly, context),
                blockCoord(args.get(1), x, z, ly, context), target);
    }

    /** {@code blockis(x, z, y, 方块)}：指定 y 处是否指定方块。 */
    private static boolean blockIsAt(List<ExprNode> args, int x, int z, int ly, EvalContext context) {
        TerrainView view = TERRAIN_VIEW.get();
        if (view == null) return false;
        BlockState target = blockValue(args.get(3), x, z, ly, context);
        if (target == null) return false;
        return view.blockIs(blockCoord(args.get(0), x, z, ly, context),
                blockCoord(args.get(1), x, z, ly, context),
                blockCoord(args.get(2), x, z, ly, context), target);
    }

    /** 坐标实参：向下取整（与方块坐标的负数语义一致）。 */
    private static int blockCoord(ExprNode arg, int x, int z, int ly, EvalContext context) {
        return (int) Math.floor(evalNumber(arg, x, z, ly, context));
    }

    /** 方块实参（surfis 第 3 参 / blockis 第 4 参）：以方块语义求值并取方块种类。 */
    private static BlockState blockValue(ExprNode arg, int x, int z, int ly, EvalContext context) {
        Object value = evalBlockArg(arg, x, z, ly, context);
        return value instanceof BlockState state ? state : null;
    }

    /** 摘掉群系求值环境求值一段表达式（方块参数位需要用方块注册表的语义）。 */
    private static Object evalBlockArg(ExprNode arg, int x, int z, int ly, EvalContext context) {
        Function<String, Object> savedResolver = BIOME_RESOLVER.get();
        TerrainView savedTerrain = TERRAIN_VIEW.get();
        BIOME_RESOLVER.remove();
        TERRAIN_VIEW.remove();
        try {
            return eval(arg, x, z, ly, context);
        } finally {
            if (savedResolver != null) BIOME_RESOLVER.set(savedResolver);
            if (savedTerrain != null) TERRAIN_VIEW.set(savedTerrain);
        }
    }

    // ---------------------------------------------------------- 方块层的群系查询

    /** {@code biomeis(x, z, y, 群系)}：指定位置的"已存储群系"是否该群系（末位是群系字面量或群系表达式）。 */
    private static boolean biomeIs(List<ExprNode> args, int x, int z, int ly, EvalContext context) {
        BiomeView view = BIOME_VIEW.get();
        if (view == null) return false;
        String biomeId = biomeIdValue(args.get(3), x, z, ly, context);
        if (biomeId == null) return false;
        return view.isBiome(blockCoord(args.get(0), x, z, ly, context),
                blockCoord(args.get(2), x, z, ly, context),
                blockCoord(args.get(1), x, z, ly, context),
                biomeId);
    }

    /**
     * 群系实参（{@code biomeis} 第 4 参）：按"群系字面量"语义求值并取 id 字符串——
     * 单个字面量或三元等群系表达式都支持。
     *
     * <p>方块层求值时字面量本会解析成方块，这里临时装一个恒等 resolver，
     * 把字面量解析成它自己的 id（与 biome 表达式里的字面量解析同路径）。
     */
    private static String biomeIdValue(ExprNode arg, int x, int z, int ly, EvalContext context) {
        Object value;
        if (arg instanceof ExprNode.BlockNode b) {
            value = b.blockId();
        } else {
            Function<String, Object> saved = BIOME_RESOLVER.get();
            BIOME_RESOLVER.set(id -> id);
            try {
                value = eval(arg, x, z, ly, context);
            } finally {
                if (saved != null) BIOME_RESOLVER.set(saved);
                else BIOME_RESOLVER.remove();
            }
        }
        return value instanceof String id ? id : null;
    }

    private static int pickIndex(int x, int z, int y, int bound) {
        if (bound <= 0) return 0;
        int h = (x * 374761393) ^ (z * 668265263) ^ (y * 997307);
        h = h ^ (h >>> 16);
        return Math.floorMod(h & 0x7FFFFFFF, bound);
    }

    /**
     * {@code seedhash(a, b, ...)}：把世界种子与全部实参做 SplitMix64 混合，返回 [0,1)。
     *
     * <p>与 {@code rand}/{@code randexcept} 不同，本函数使用**完整的 64 位**种子与实参的
     * 位模式，因此适合做"随世界种子变化"的噪声原语：
     * {@code seedhash(x, z, 0) < 0.5 ? A : B}；同一世界、同一参数永远得到同一结果。
     *
     * <p><b>算法是兼容性契约，一经发布不得更改</b>——与 {@link #pickIndex} 同等对待，
     * 否则同一公式与种子会在不同版本间生成不同地形。
     */
    private static double seedhash(List<ExprNode> args, int x, int z, int ly, EvalContext context) {
        long h = Noise.worldSeed;
        for (ExprNode arg : args) {
            h = Noise.mix64(h + 0x9E3779B97F4A7C15L
                    + Double.doubleToRawLongBits(evalNumber(arg, x, z, ly, context)));
        }
        // 取高 53 位，乘以 2^-53 得到 [0,1)
        return (Noise.mix64(h) >>> 11) * 0x1.0p-53;
    }

    // ---------------------------------------------------------- 自然世界原语（1.2.5）
    //
    // 与 seedhash / pickIndex 同级：**算法是兼容性契约，一经发布不得更改**。

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    /** 0..1 输入的三次平滑（hermite）。 */
    private static double smoothstep(double t) {
        double c = clamp(t, 0, 1);
        return c * c * (3 - 2 * c);
    }

    /** vanilla {@code Mth.map}：把 [a, b] 线性映射到 [c, d]；a == b 时返回 c。 */
    private static double mapValue(double v, double a, double b, double c, double d) {
        return b == a ? c : c + (v - a) / (b - a) * (d - c);
    }

    // ---------------------------------------------------------- 数学助手（1.3.0）
    //
    // 与噪声原语同级：算法冻结（见 Noise 的说明）。全部为纯函数。

    /** {@code atan2(a, b)} = Math.atan2(a, b)。 */
    private static double atan2(double a, double b) {
        return Math.atan2(a, b);
    }

    /** {@code fract(v)}：小数部分 v - floor(v)（负数也返回 [0,1)）。 */
    private static double fract(double v) {
        return v - Math.floor(v);
    }

    /** {@code step(edge, v)}：v < edge 为 0，否则 1（GLSL 风格参数序）。 */
    private static double stepValue(double edge, double v) {
        return v < edge ? 0 : 1;
    }

    /** {@code smootherstep(t)}：0..1 输入的五次平滑（6t^5 - 15t^4 + 10t^3）。 */
    private static double smootherstep(double t) {
        double c = clamp(t, 0, 1);
        return c * c * c * (c * (c * 6 - 15) + 10);
    }

    /** {@code hypot(a, b)}：sqrt(a² + b²)（不用 Math.hypot，保证逐位确定）。 */
    private static double hypot(double a, double b) {
        return Math.sqrt(a * a + b * b);
    }

    /** {@code bias(v, b)}：Schlick bias；v 夹在 [0,1]，b 夹在 (0,1)，b=0.5 时约等于恒等。 */
    private static double bias(double v, double b) {
        double t = clamp(v, 0, 1);
        double bb = clamp(b, 1e-6, 1 - 1e-6);
        return t / ((1 / bb - 2) * (1 - t) + 1);
    }

    /** {@code gain(v, g)}：Schlick gain，以 0.5 为枢轴的对称 bias；g=0.5 时约等于恒等。 */
    private static double gain(double v, double g) {
        double t = clamp(v, 0, 1);
        double gg = clamp(g, 1e-6, 1 - 1e-6);
        return t < 0.5 ? 0.5 * bias(2 * t, gg) : 1 - 0.5 * bias(2 - 2 * t, gg);
    }

    /** {@code saturate(v)}：夹取到 [0, 1]。 */
    private static double saturate(double v) {
        return clamp(v, 0, 1);
    }

    /**
     * {@code terrace(v, n, sharp)}：阶地映射——把连续的 v 变成以 1/n 为台阶高度的
     * 阶梯；每个台阶末端（fract(v·n) 接近 1 处）有一段宽度约 1/sharp 的平滑过渡。
     * n 夹在 [1, 64]，sharp 夹在 [1, 256]。算法冻结。
     */
    private static double terrace(double v, double n, double sharp) {
        double steps = clamp(n, 1, 64);
        double edge = clamp(sharp, 1, 256);
        double u = v * steps;
        double i = Math.floor(u);
        double f = u - i;
        double t = smoothstep(clamp((f - 1 + 1 / edge) * edge, 0, 1));
        return (i + t) / steps;
    }

    /** min/max 的多参折叠（≥2 个参数；编译与未编译路径共用）。 */
    private static double foldMinMax(List<ExprNode> args, boolean max, int x, int z, int ly, EvalContext context) {
        double acc = evalNumber(args.get(0), x, z, ly, context);
        for (int i = 1; i < args.size(); i++) {
            double v = evalNumber(args.get(i), x, z, ly, context);
            acc = max ? Math.max(acc, v) : Math.min(acc, v);
        }
        return acc;
    }

    /** fbm2 的双形态：5 参（固定 gain/lacunarity）与 7 参（可调 + 每倍频偏移）。 */
    private static double evalFbm2(List<ExprNode> args, int x, int z, int ly, EvalContext context) {
        double px = evalNumber(args.get(0), x, z, ly, context);
        double pz = evalNumber(args.get(1), x, z, ly, context);
        double scale = evalNumber(args.get(2), x, z, ly, context);
        double octaves = evalNumber(args.get(3), x, z, ly, context);
        double salt = evalNumber(args.get(4), x, z, ly, context);
        if (args.size() == 7) {
            return Noise.fbm2(px, pz, scale, octaves, salt,
                    evalNumber(args.get(5), x, z, ly, context),
                    evalNumber(args.get(6), x, z, ly, context));
        }
        return Noise.fbm2(px, pz, scale, octaves, salt);
    }

    /** fbma2 的振幅列表（第 5 个参数起）。 */
    private static double[] fbmaAmplitudes(List<ExprNode> args, int x, int z, int ly, EvalContext context) {
        double[] amplitudes = new double[args.size() - 4];
        for (int i = 4; i < args.size(); i++) {
            amplitudes[i - 4] = evalNumber(args.get(i), x, z, ly, context);
        }
        return amplitudes;
    }

    // ---------------------------------------------------------- 空间助手（1.3.0）
    //
    // 全部用中心差分、步长 1 格；对参数表达式在偏移坐标处重新求值（纯函数，无副作用）。
    // 外层 let 绑定在偏移求值中读同一槽位（按中心列的值参与差分），语义见指南。

    /** 中心差分梯度 {gx, gz}。 */
    private static double[] gradient(ExprNode expr, int x, int z, int ly, EvalContext context) {
        double gx = (evalNumber(expr, x + 1, z, ly, context) - evalNumber(expr, x - 1, z, ly, context)) / 2;
        double gz = (evalNumber(expr, x, z + 1, ly, context) - evalNumber(expr, x, z - 1, ly, context)) / 2;
        return new double[]{gx, gz};
    }

    /** {@code slope(expr)}：√(gx² + gz²)（中心差分，步长 1 格）。 */
    private static double slopeOf(List<ExprNode> args, int x, int z, int ly, EvalContext context) {
        double[] g = gradient(args.get(0), x, z, ly, context);
        return Math.sqrt(g[0] * g[0] + g[1] * g[1]);
    }

    /** {@code curv(expr)}：拉普拉斯（x/z 两个方向中心二阶差分之和）。 */
    private static double curvOf(List<ExprNode> args, int x, int z, int ly, EvalContext context) {
        ExprNode e = args.get(0);
        return evalNumber(e, x + 1, z, ly, context) + evalNumber(e, x - 1, z, ly, context)
                + evalNumber(e, x, z + 1, ly, context) + evalNumber(e, x, z - 1, ly, context)
                - 4 * evalNumber(e, x, z, ly, context);
    }

    /** {@code isodist(expr)}：|v| / |∇v|（到零等值线的距离估计；梯度近零时取 1e9）。 */
    private static double isodistOf(List<ExprNode> args, int x, int z, int ly, EvalContext context) {
        ExprNode e = args.get(0);
        double v = evalNumber(e, x, z, ly, context);
        double[] g = gradient(e, x, z, ly, context);
        double magnitude = Math.sqrt(g[0] * g[0] + g[1] * g[1]);
        return magnitude < 1e-9 ? 1e9 : Math.abs(v) / magnitude;
    }

    // ---------------------------------------------------------- 样条映射（1.2.6）

    /**
     * {@code spline(v, p0, v0, p1, v1, ...)}：分段线性映射。
     *
     * <p>点按位置升序（{@code p0 < p1 < ...}）；{@code v} 在相邻两点之间线性插值，
     * 越界取端点值（含 {@code v <= p0} 与 {@code v >= pn}）。重复位置取后一个点的值。
     * 算法冻结：发布后不得更改（同 noise/worley）。
     */
    private static double splineLinear(List<ExprNode> args, int x, int z, int ly, EvalContext context) {
        double v = evalNumber(args.get(0), x, z, ly, context);
        int points = (args.size() - 1) / 2;
        double prevP = evalNumber(args.get(1), x, z, ly, context);
        double prevV = evalNumber(args.get(2), x, z, ly, context);
        if (v <= prevP) return prevV;
        for (int i = 1; i < points; i++) {
            double p = evalNumber(args.get(1 + 2 * i), x, z, ly, context);
            double pv = evalNumber(args.get(2 + 2 * i), x, z, ly, context);
            if (v <= p) {
                return p == prevP ? pv : prevV + (v - prevP) * (pv - prevV) / (p - prevP);
            }
            prevP = p;
            prevV = pv;
        }
        return prevV;
    }

    /**
     * {@code cspline(v, p0, v0, p1, v1, ...)}：Catmull-Rom 样条（非均匀间隔的
     * 三次 Hermite 形式，切线由相邻点的差分估计）。
     *
     * <p>曲线经过所有点、在点处 C1 连续（比 {@code spline} 更顺滑、可能出现
     * 轻微过冲）；越界取端点值。点按位置升序；重复位置退化处理（不产生除零）。
     * 算法冻结：发布后不得更改。
     */
    private static double splineCatmullRom(List<ExprNode> args, int x, int z, int ly, EvalContext context) {
        double v = evalNumber(args.get(0), x, z, ly, context);
        int points = (args.size() - 1) / 2;
        double[] ps = new double[points];
        double[] vs = new double[points];
        for (int i = 0; i < points; i++) {
            ps[i] = evalNumber(args.get(1 + 2 * i), x, z, ly, context);
            vs[i] = evalNumber(args.get(2 + 2 * i), x, z, ly, context);
        }
        if (v <= ps[0]) return vs[0];
        if (v >= ps[points - 1]) return vs[points - 1];
        int i = 0;
        while (i < points - 2 && v > ps[i + 1]) i++;
        double h = ps[i + 1] - ps[i];
        double t = h == 0 ? 0 : (v - ps[i]) / h;
        // 切线：内部用相邻点中心差分，端点用单侧差分（对应 Catmull-Rom 的端点处理）
        double m0 = i == 0
                ? splineSlope(ps[0], vs[0], ps[1], vs[1])
                : splineSlope(ps[i - 1], vs[i - 1], ps[i + 1], vs[i + 1]);
        double m1 = i + 1 == points - 1
                ? splineSlope(ps[points - 2], vs[points - 2], ps[points - 1], vs[points - 1])
                : splineSlope(ps[i], vs[i], ps[i + 2], vs[i + 2]);
        double t2 = t * t;
        double t3 = t2 * t;
        return (2 * t3 - 3 * t2 + 1) * vs[i]
                + (t3 - 2 * t2 + t) * h * m0
                + (-2 * t3 + 3 * t2) * vs[i + 1]
                + (t3 - t2) * h * m1;
    }

    private static double splineSlope(double pa, double va, double pb, double vb) {
        return pa == pb ? 0 : (vb - va) / (pb - pa);
    }

    /**
     * {@code waterline(x, z, level)}：该列的水位面高度——以 {@code level} 为基准
     * 叠加缓变噪声（特征尺度约 512 格、实际幅度约 ±3 格），随世界种子变化。
     *
     * <p>把它与地形高度（如 {@code h}）比较即可写"低地淹水 / 洞穴蓄水"：
     * {@code y <= h ? (y <= w ? minecraft:water : ...) : ...}。算法冻结。
     */
    private static double waterline(List<ExprNode> args, int x, int z, int ly, EvalContext context) {
        double wx = evalNumber(args.get(0), x, z, ly, context);
        double wz = evalNumber(args.get(1), x, z, ly, context);
        double level = evalNumber(args.get(2), x, z, ly, context);
        return level + Noise.fbm2(wx, wz, 512, 3, 17) * 6;
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
        return evalToBlock(node, x, z, ly, ly);
    }

    /** 方块语义入口（带绝对 y，供内置变量 {@code y} 使用）。 */
    public static BlockState evalToBlock(ExprNode node, int x, int z, int ly, int globalY) {
        // 方块语义入口：临时摘掉群系求值环境。biome 行的地形查询会在求值中途回调
        // 到方块层（FormulaLayerDef.getBlock 同样走这里），不摘除的话方块字面量
        // 会被当成群系解析。
        Function<String, Object> savedResolver = BIOME_RESOLVER.get();
        TerrainView savedTerrain = TERRAIN_VIEW.get();
        BIOME_RESOLVER.remove();
        TERRAIN_VIEW.remove();
        try {
            Object result = evalAt(node, x, z, ly, globalY);
            return result instanceof BlockState bs ? bs : BlockResolver.resolve("minecraft:air");
        } finally {
            if (savedResolver != null) BIOME_RESOLVER.set(savedResolver);
            if (savedTerrain != null) TERRAIN_VIEW.set(savedTerrain);
        }
    }

    /**
     * 群系求值：与 {@link #evalAt} 相同，但字面量不解析为方块，而是交给
     * {@code resolver}（群系表达式里的字面量是群系 id）；同时注入 biome 行的
     * 地形查询视图（可为 null）。返回 resolver 的原样结果。
     */
    public static Object evalToBiome(ExprNode node, int x, int z, int ly, int globalY,
                                     Function<String, Object> resolver, TerrainView terrain) {
        BIOME_RESOLVER.set(resolver);
        TERRAIN_VIEW.set(terrain);
        try {
            return evalAt(node, x, z, ly, globalY);
        } finally {
            BIOME_RESOLVER.remove();
            TERRAIN_VIEW.remove();
        }
    }

    private static double toDouble(Object o) { if (o instanceof Number n) return n.doubleValue(); if (o instanceof Boolean b) return b ? 1d : 0d; return 0d; }
    private static boolean toBool(Object o) { if (o instanceof Boolean b) return b; if (o instanceof Number n) return n.doubleValue() != 0; return false; }

    private static final class EvalContext {
        private static final Object MISSING = new Object();

        private final Map<String, Object> bindings = new HashMap<>();
        private final ArrayDeque<Scope> scopes = new ArrayDeque<>();
        /** 当前求值的绝对 y（内置变量 {@code y}）；无层上下文时等于 ly。 */
        private int globalY;
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

        /** 元组槽位的第 index 个分量；缺失（未求值 / 越界）时回退 0。 */
        double tupleComponent(int slot, int index) {
            Object value = slots[slot];
            if (value instanceof double[] values && index < values.length) return values[index];
            return 0;
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
            return bindings.containsKey(name) ? bindings.get(name) : builtinValue(name, x, z, ly, globalY);
        }
    }

    private static final class Scope {
        private final Map<String, Object> previous = new HashMap<>();
    }
}
