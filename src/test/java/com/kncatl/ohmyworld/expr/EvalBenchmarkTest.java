package com.kncatl.ohmyworld.expr;

import java.util.Arrays;
import java.util.Locale;

import org.junit.jupiter.api.Test;

/**
 * 临时诊断基准：测量不同形态表达式的逐次求值吞吐，用于定位区块生成热点。
 * 只使用不依赖 Minecraft 类型的表达式，因此可在 test 源集中编译。
 * 不作为断言型测试。
 */
class EvalBenchmarkTest {

    private static final int COLUMNS = 16 * 16; // 一个区块的水平列数
    private static final int HEIGHT = 128;      // 典型层高（默认公式的第二层）
    private static final int REPEATS = 4;

    private static ExprNode parse(String expr) {
        return new ExprParser(ExprLexer.tokenize(expr)).parse();
    }

    /** 模拟 fillChunk 的遍历顺序：y 外层、x/z 内层，共 HEIGHT*COLUMNS 次求值。 */
    private static long run(ExprNode node, int repeats) {
        long sink = 0;
        for (int r = 0; r < repeats; r++) {
            for (int y = -63; y <= 64; y++) {
                int ly = y + 63;
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        sink += System.identityHashCode(ExprEvaluator.eval(node, x, z, ly));
                    }
                }
            }
        }
        return sink;
    }

    private static void bench(String label, String expr) {
        ExprNode node = parse(expr);
        for (int i = 0; i < 8; i++) run(node, 1); // 充分预热，避免 JIT 未编译导致的偏差

        // 取最小值：微基准里最小值比中位数更接近真实成本，
        // 也更能反映「无 GC 干扰时」的求值开销。
        long best = Long.MAX_VALUE;
        for (int i = 0; i < 7; i++) {
            long t0 = System.nanoTime();
            run(node, REPEATS);
            best = Math.min(best, System.nanoTime() - t0);
        }

        long evals = (long) REPEATS * HEIGHT * COLUMNS;
        double perEval = (double) best / evals;
        double perChunkMs = perEval * HEIGHT * COLUMNS / 1_000_000.0;
        System.out.printf(Locale.ROOT,
                "  %-44s %7.1f ns/次   %8.2f ms/区块(256列x128高)%n", label, perEval, perChunkMs);
    }

    @Test
    void measureEvalThroughput() {
        System.out.println();
        System.out.println("--- 逐次求值吞吐（1 区块 = 256 列 x 128 高 = 32768 次）---");
        // 注意：test 运行时不包含 Minecraft 类，因此这里全部使用
        // 不触发 BlockState 类加载的等价写法（避开 == / != / rand）。
        bench("常量", "1");
        bench("单次比较", "x < 0");
        bench("奇偶判定 (x+z)%2 < 1", "(x + z) % 2 < 1");
        bench("运算密集", "((x * 31 + z * 17) % 7 + (x - z) % 5) > 3");
        bench("三角函数", "sin(x * 0.1) * cos(z * 0.1) > 0");
        bench("let 绑定", "{ let a = x + z; let b = a * 2; b % 3 < 1 }");
        bench("嵌套条件", "x > 8 ? (z > 8 ? 1 : 2) : (z > 8 ? 3 : 4)");
        bench("逻辑与/非", "x > 0 && !(z > 0)");
        System.out.println();

        measureColumnHoisting();
        measurePartialHoisting();
    }

    /**
     * B3 部分提升：整层与 y 相关、但部分绑定与 y 无关时，
     * y 外层遍历会让每个格子都换一列 (x, z)，列预备退化成逐格重算；
     * 列外层遍历（{@code PatternData} 优化后的顺序）则每列只预备一次。
     */
    private static void measurePartialHoisting() {
        System.out.println("--- 部分绑定提升：列外层 vs y 外层（1 层 = 256 列 x 128 高）---");
        benchOrder("三角函数绑定 + ly 判定", ExprCompiler.compile(parse(
                "{ let t = sin(x * 0.1) * cos(z * 0.1); let u = x * 2 + z * 3; let v = ly > 64 ? t + u : t - u; v }")));
        benchOrder("多重绑定", ExprCompiler.compile(parse(
                "{ let a = x * 7 + z; let b = a * a - z; let c = (ly % 16) * 3; a + b + c > 500 }")));
        System.out.println();
    }

    private static void benchOrder(String label, ExprNode node) {
        Object[] buffer = new Object[HEIGHT * COLUMNS];
        for (int i = 0; i < 8; i++) { // 预热
            fillPerCell(node, buffer);
            fillColumnOuter(node, buffer);
        }

        long yOuter = Long.MAX_VALUE;
        long columnOuter = Long.MAX_VALUE;
        for (int i = 0; i < 7; i++) {
            long t0 = System.nanoTime();
            fillPerCell(node, buffer);
            yOuter = Math.min(yOuter, System.nanoTime() - t0);

            long t1 = System.nanoTime();
            fillColumnOuter(node, buffer);
            columnOuter = Math.min(columnOuter, System.nanoTime() - t1);
        }

        System.out.printf(Locale.ROOT,
                "  %-30s y 外层 %7.3f ms  →  列外层 %7.3f ms   加速 %5.2f 倍%n",
                label, yOuter / 1e6, columnOuter / 1e6, (double) yOuter / Math.max(1, columnOuter));
    }

    private static void fillColumnOuter(ExprNode node, Object[] buffer) {
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = 0; y < HEIGHT; y++) {
                    buffer[y * COLUMNS + x * 16 + z] = ExprEvaluator.eval(node, x, z, y);
                }
            }
        }
    }

    /**
     * 对比一层的两种写入方式：
     *   逐格求值                    —— 优化前的行为
     *   按列求值一次 + 整段拷贝      —— 优化后（层不引用 ly 时）
     */
    private static void benchColumn(String label, ExprNode node) {
        Object[] buffer = new Object[HEIGHT * COLUMNS];

        for (int i = 0; i < 6; i++) { // 预热
            fillPerCell(node, buffer);
            fillPerColumn(node, buffer);
        }

        long naive = Long.MAX_VALUE;
        long hoisted = Long.MAX_VALUE;
        for (int i = 0; i < 6; i++) {
            long t0 = System.nanoTime();
            fillPerCell(node, buffer);
            naive = Math.min(naive, System.nanoTime() - t0);

            long t1 = System.nanoTime();
            fillPerColumn(node, buffer);
            hoisted = Math.min(hoisted, System.nanoTime() - t1);
        }

        System.out.printf(Locale.ROOT,
                "  %-30s 逐格 %7.3f ms  →  按列 %7.3f ms   加速 %5.1f 倍%n",
                label, naive / 1e6, hoisted / 1e6, (double) naive / Math.max(1, hoisted));
    }

    private static void fillPerCell(ExprNode node, Object[] buffer) {
        for (int y = 0; y < HEIGHT; y++) {
            int base = y * COLUMNS;
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    buffer[base + x * 16 + z] = ExprEvaluator.eval(node, x, z, y);
                }
            }
        }
    }

    private static void fillPerColumn(ExprNode node, Object[] buffer) {
        Object[] column = new Object[COLUMNS];
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                column[x * 16 + z] = ExprEvaluator.eval(node, x, z, 0);
            }
        }
        for (int y = 0; y < HEIGHT; y++) {
            System.arraycopy(column, 0, buffer, y * COLUMNS, COLUMNS);
        }
    }

    private static void measureColumnHoisting() {
        System.out.println("--- 按列复用 vs 逐格求值（1 层 = 256 列 x 128 高）---");
        benchColumn("奇偶判定 (x+z)%2 < 1", parse("(x + z) % 2 < 1"));
        benchColumn("三角函数", parse("sin(x * 0.1) * cos(z * 0.1) > 0"));
        benchColumn("let 绑定", parse("{ let a = x + z; let b = a * 2; b % 3 < 1 }"));
        System.out.println();
    }
}
