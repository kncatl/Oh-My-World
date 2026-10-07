package com.kncatl.ohmyworld;

import org.junit.jupiter.api.Test;

import com.kncatl.ohmyworld.expr.ExprCompiler;
import com.kncatl.ohmyworld.expr.ExprEvaluator;
import com.kncatl.ohmyworld.expr.ExprLexer;
import com.kncatl.ohmyworld.expr.ExprNode;
import com.kncatl.ohmyworld.expr.ExprParser;

/**
 * 求值成本基准（只打印、不做性能断言）：为 1.2.6「惰性列内提升」的取舍提供数据。
 *
 * <p>测量两组对照：
 * <ul>
 *   <li>共享 let 形式：重计算被列内提升，<b>每列无条件求值</b>；</li>
 *   <li>分支形式：重计算写在三元分支里，<b>只有命中的列才求值</b>（引擎惰性）。</li>
 * </ul>
 * 数值为测试 JVM 上的指示性结果（非 JMH 级别），看的是量级与倍率。
 */
class ExprBenchmarkTest {

    private static final int SIDE = 512;              // 512×512 列
    private static final int COLUMNS = SIDE * SIDE;
    private static final int WARMUP = 4096;

    /** 重计算：与「局部构造」同量级（fbm3 + fbm2，共 7 次噪声采样）。 */
    private static final String HEAVY = "fbm3(x, 0, z, 6, 4, 1) + fbm2(x, z, 6, 4, 2)";
    /** 约 2% 的列命中。 */
    private static final String RARE = "(seedhash(x, z, 99) < 0.02)";

    @Test
    void hoistingVersusLazyBranch() {
        ExprEvaluator.setWorldSeed(12345L);

        // A：共享 let（等价于节级 let；编译后绑定被列内提升 → 每列无条件算）
        ExprNode hoisted = compile("{ let h = " + HEAVY + "; " + RARE + " ? h : 0 }");
        // B：分支形式（重计算写进三元分支 → 只有命中的列才算）
        ExprNode branch = compile(RARE + " ? (" + HEAVY + ") : 0");

        long timeHoisted = time(hoisted);
        long timeBranch = time(branch);
        System.out.printf("[bench] 共享 let（每列无条件）: %,d ms / %,d 列 = %.0f ns/列%n",
                timeHoisted / 1_000_000, COLUMNS, timeHoisted / (double) COLUMNS);
        System.out.printf("[bench] 分支内惰性（2%% 列）: %,d ms / %,d 列 = %.0f ns/列%n",
                timeBranch / 1_000_000, COLUMNS, timeBranch / (double) COLUMNS);
        System.out.printf("[bench] 分支形式快 %.1f 倍%n",
                timeHoisted / (double) Math.max(timeBranch, 1));

        // 两者结果一致（抽查）
        for (int i = 0; i < 64; i++) {
            Object a = ExprEvaluator.evalAt(hoisted, i * 17, i * 29, 0, 64);
            Object b = ExprEvaluator.evalAt(branch, i * 17, i * 29, 0, 64);
            if (!String.valueOf(a).equals(String.valueOf(b))) {
                throw new AssertionError("结果不一致 @" + i + ": " + a + " vs " + b);
            }
        }
    }

    @Test
    void primitiveThroughput() {
        ExprEvaluator.setWorldSeed(12345L);
        String[] expressions = {
                "seedhash(x, z, 1)",
                "noise2(x, z, 64, 1)",
                "noise3(x, 0, z, 64, 1)",
                "fbm2(x, z, 64, 3, 1)",
                "fbm3(x, 0, z, 64, 3, 1)",
                "worley2(x, z, 64, 1)",
                "sqrt(x * x + z * z)",
        };
        for (String expression : expressions) {
            ExprNode node = compile(expression);
            long ns = time(node);
            System.out.printf("[bench] %-26s %.0f ns/次%n", expression, ns / (double) COLUMNS);
        }
    }

    private static ExprNode compile(String expression) {
        return ExprCompiler.compile(new ExprParser(ExprLexer.tokenize(expression)).parse());
    }

    private static long time(ExprNode node) {
        for (int i = 0; i < WARMUP; i++) {
            ExprEvaluator.evalAt(node, i, i * 3, 0, 64);
        }
        long start = System.nanoTime();
        for (int x = 0; x < SIDE; x++) {
            for (int z = 0; z < SIDE; z++) {
                ExprEvaluator.evalAt(node, x * 7 + 3, z * 13 + 5, 0, 64);
            }
        }
        return System.nanoTime() - start;
    }
}
