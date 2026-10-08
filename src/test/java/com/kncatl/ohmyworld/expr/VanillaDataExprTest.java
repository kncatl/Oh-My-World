package com.kncatl.ohmyworld.expr;

import org.junit.jupiter.api.Test;

import com.kncatl.ohmyworld.FormulaParser;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 原版数据（M3.2，1.3.2）：df() / noise() 的视图映射、坐标缩放与无视图回退。
 */
class VanillaDataExprTest {

    /** 可观察视图：记录最近一次 density/noise 调用参数，返回由参数拼出的确定值。 */
    private static final class RecordingView implements ExprEvaluator.VanillaView {
        String densityId;
        int dx;
        int dy;
        int dz;
        String noiseId;
        double nx;
        double ny;
        double nz;

        @Override
        public double field(int code, int x, int y, int z) {
            return 0;
        }

        @Override
        public double density(String id, int x, int y, int z) {
            this.densityId = id;
            this.dx = x;
            this.dy = y;
            this.dz = z;
            return 1000 + x * 100 + y * 10 + z;
        }

        @Override
        public double noise(String id, double x, double y, double z) {
            this.noiseId = id;
            this.nx = x;
            this.ny = y;
            this.nz = z;
            return x * 100 + y * 10 + z;
        }

        int vx;
        int vz;

        @Override
        public double vheight(int x, int z) {
            this.vx = x;
            this.vz = z;
            return 100 + x * 0.5 + z;
        }
    }

    private static double eval(String source, int x, int z, int y) {
        ExprNode node = ExprCompiler.compile(
                FormulaParser.expandLoops(new ExprParser(ExprLexer.tokenize(source)).parse()));
        return ((Number) ExprEvaluator.evalAt(node, x, z, 0, y)).doubleValue();
    }

    @Test
    void dfReadsTheInjectedView() {
        RecordingView view = new RecordingView();
        ExprEvaluator.setVanillaView(view);
        try {
            assertEquals(1000 + 10 * 100 + 2 * 10 + 3,
                    eval("df(minecraft:overworld/ridges, 10, 2, 3)", 0, 0, 0), 1e-9);
            assertEquals("minecraft:overworld/ridges", view.densityId);
            assertEquals(10, view.dx);
            assertEquals(2, view.dy);
            assertEquals(3, view.dz);

            // 坐标可以是表达式；y 用绝对高度
            assertEquals(1000 + 5 * 100 + 20 + 7,
                    eval("df(minecraft:overworld/ridges, x + 5, 2, z + 7)", 0, 0, 0), 1e-9);
        } finally {
            ExprEvaluator.setVanillaView(null);
        }
    }

    @Test
    void noiseScalesCoordinates() {
        RecordingView view = new RecordingView();
        ExprEvaluator.setVanillaView(view);
        try {
            eval("noise(minecraft:temperature, 10, 2, 3)", 0, 0, 0);
            assertEquals(10, view.nx, 1e-9);
            assertEquals(2, view.ny, 1e-9);
            assertEquals(3, view.nz, 1e-9);

            // 只给 xzScale：x/z 缩放，y 不变
            eval("noise(minecraft:temperature, 10, 2, 3, 2)", 0, 0, 0);
            assertEquals(20, view.nx, 1e-9);
            assertEquals(2, view.ny, 1e-9);
            assertEquals(6, view.nz, 1e-9);

            // xzScale + yScale
            eval("noise(minecraft:temperature, 10, 2, 3, 2, 5)", 0, 0, 0);
            assertEquals(20, view.nx, 1e-9);
            assertEquals(10, view.ny, 1e-9);
            assertEquals(6, view.nz, 1e-9);
        } finally {
            ExprEvaluator.setVanillaView(null);
        }
    }

    @Test
    void vheightReadsTheInjectedView() {
        RecordingView view = new RecordingView();
        ExprEvaluator.setVanillaView(view);
        try {
            assertEquals(100 + 10 * 0.5 + 3, eval("vheight(10, 3)", 0, 0, 0), 1e-9);
            assertEquals(10, view.vx);
            assertEquals(3, view.vz);
            // 坐标可以是表达式（先向下取整）
            assertEquals(100 + 5 * 0.5 + 7, eval("vheight(x + 5.9, z + 7.2)", 0, 0, 0), 1e-9);
            assertEquals(5, view.vx);
            assertEquals(7, view.vz);
        } finally {
            ExprEvaluator.setVanillaView(null);
        }
    }

    @Test
    void returnsZeroWithoutAView() {
        assertEquals(0, eval("df(minecraft:overworld/ridges, 10, 2, 3)", 0, 0, 0), 1e-12);
        assertEquals(0, eval("noise(minecraft:temperature, 10, 2, 3)", 0, 0, 0), 1e-12);
        assertEquals(0, eval("vheight(10, 3)", 0, 0, 0), 1e-12);
    }
}
