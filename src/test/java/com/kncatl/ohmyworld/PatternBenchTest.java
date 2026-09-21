package com.kncatl.ohmyworld;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import com.kncatl.ohmyworld.expr.ExprCompiler;
import com.kncatl.ohmyworld.expr.ExprEvaluator;
import com.kncatl.ohmyworld.expr.ExprLexer;
import com.kncatl.ohmyworld.expr.ExprNode;
import com.kncatl.ohmyworld.expr.ExprParser;

/**
 * 真实复杂公式的逐方块求值基准。
 *
 * <p>资源里的公式由「测试巨构1.txt」第二层转换而来：方块字面量被替换成互不相同的
 * 数字。这样基准不触碰 Minecraft 注册表（test 源集没有游戏环境），但 37 个 let
 * 绑定、函数调用与三元链的**计算结构完全一致**——而计算量正是本基准要测的对象。
 *
 * <p>该资源是从作者本人的公式派生的创作内容，**刻意不纳入版本控制**（见
 * {@code .gitignore}）。资源缺失时本测试自动跳过，因此克隆仓库后不会失败。
 * 想在本机复现基准，把派生公式放到
 * {@code src/test/resources/bench-mega-formula.txt} 即可。
 *
 * <p>只打印数据，不做断言。
 */
class PatternBenchTest {

    /** 该公式所在层为 y=-63..319，故 ly = y + 63，取值 0..382。 */
    private static final int LY_START = 0;
    private static final int LY_END = 382;
    private static final int WARMUP = 4;
    private static final int SAMPLES = 7;

    /** @return 公式文本；资源缺失时返回 null（由调用方跳过测试） */
    private static String loadFormula() {
        try (InputStream in = PatternBenchTest.class.getResourceAsStream("/bench-mega-formula.txt")) {
            if (in == null) return null;
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 按 fillChunk 的顺序遍历：ly 外层、x/z 内层。 */
    private static long run(ExprNode node) {
        long sink = 0;
        for (int ly = LY_START; ly <= LY_END; ly++) {
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    sink += System.identityHashCode(ExprEvaluator.eval(node, x, z, ly));
                }
            }
        }
        return sink;
    }

    @Test
    void megaFormulaCost() {
        String formula = loadFormula();
        Assumptions.assumeTrue(formula != null,
                "缺少 src/test/resources/bench-mega-formula.txt（派生自作者本人的公式，刻意不入库）");

        ExprNode raw = new ExprParser(ExprLexer.tokenize(formula)).parse();
        ExprNode compiled = ExprCompiler.compile(raw);

        System.out.println();
        System.out.println("--- 巨构公式逐方块求值 ---");
        System.out.printf("  该层共 %d 格/区块%n", (LY_END - LY_START + 1) * 16 * 16);
        measure("未编译（改造前）", raw);
        measure("已编译（改造后）", compiled);
        System.out.println();
    }

    private static void measure(String label, ExprNode node) {
        for (int i = 0; i < WARMUP; i++) run(node);

        long[] samples = new long[SAMPLES];
        for (int i = 0; i < SAMPLES; i++) {
            long t0 = System.nanoTime();
            run(node);
            samples[i] = System.nanoTime() - t0;
        }
        Arrays.sort(samples);

        long blocks = (long) (LY_END - LY_START + 1) * 16 * 16;
        System.out.printf(Locale.ROOT,
                "  %-18s 单次 %8.1f ns   →  %7.1f ms/区块%n",
                label, (double) samples[0] / blocks, samples[0] / 1e6);
    }
}
