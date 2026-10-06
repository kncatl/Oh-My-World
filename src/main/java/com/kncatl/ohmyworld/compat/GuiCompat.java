package com.kncatl.ohmyworld.compat;

import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
//? >=26.1 {
import net.minecraft.client.gui.GuiGraphicsExtractor;
//?} else {
import net.minecraft.client.gui.GuiGraphics;
//?}

/**
 * 屏幕绘制的通用接口（只覆盖本模组用到的那几个绘制能力）。
 *
 * <p>26.1 起 MC 的渲染改成「先提取渲染状态」模型：{@code GuiGraphics} 被
 * {@code GuiGraphicsExtractor} 取代，{@code Screen.render(...)} 也换成了
 * {@code extractRenderState(...)}。屏幕代码只依赖这里的 {@link Draw}，
 * 版本差异全部留在本文件里。
 */
public final class GuiCompat {

    /** 版本无关的绘制能力：文字与矩形。 */
    public interface Draw {
        void text(Font font, String value, int x, int y, int color);

        void text(Font font, Component value, int x, int y, int color);

        void centered(Font font, Component value, int x, int y, int color);

        /** 填充矩形（含端点，参数顺序与 {@code GuiGraphics.fill} 一致）。 */
        void fill(int x1, int y1, int x2, int y2, int color);

        /** 压入矩形裁剪区（与 {@code GuiGraphics.enableScissor} 语义一致）。 */
        void pushClip(int x1, int y1, int x2, int y2);

        /** 弹出最近一次 {@link #pushClip} 压入的裁剪区。 */
        void popClip();
    }

    private GuiCompat() {}

    //? >=26.1 {
    public static Draw of(GuiGraphicsExtractor graphics) {
        return new Draw() {
            @Override
            public void text(Font font, String value, int x, int y, int color) {
                graphics.text(font, value, x, y, color);
            }

            @Override
            public void text(Font font, Component value, int x, int y, int color) {
                graphics.text(font, value, x, y, color);
            }

            @Override
            public void centered(Font font, Component value, int x, int y, int color) {
                graphics.centeredText(font, value, x, y, color);
            }

            @Override
            public void fill(int x1, int y1, int x2, int y2, int color) {
                graphics.fill(x1, y1, x2, y2, color);
            }

            @Override
            public void pushClip(int x1, int y1, int x2, int y2) {
                graphics.enableScissor(x1, y1, x2, y2);
            }

            @Override
            public void popClip() {
                graphics.disableScissor();
            }
        };
    }
    //?} else {
    public static Draw of(GuiGraphics graphics) {
        return new Draw() {
            // 注意：这里刻意不用 drawString —— 它的返回类型在 1.21.6 由 int 变成 void，
            // JVM 方法描述符包含返回类型，因此 1.21.5 构建的 jar 在 1.21.6+ 上会因
            // NoSuchMethodError 崩溃。drawCenteredString 自 1.21.1 起一直是 void，
            // 且其实现就是「把 drawString 左移半个字宽」——反向平移同一个宽度即可得到
            // 逐像素相同的左对齐绘制（同一 width 值，整数运算精确抵消）。
            @Override
            public void text(Font font, String value, int x, int y, int color) {
                graphics.drawCenteredString(font, value, x + font.width(value) / 2, y, color);
            }

            @Override
            public void text(Font font, Component value, int x, int y, int color) {
                graphics.drawCenteredString(font, value,
                        x + font.width(value.getVisualOrderText()) / 2, y, color);
            }

            @Override
            public void centered(Font font, Component value, int x, int y, int color) {
                graphics.drawCenteredString(font, value, x, y, color);
            }

            @Override
            public void fill(int x1, int y1, int x2, int y2, int color) {
                graphics.fill(x1, y1, x2, y2, color);
            }

            @Override
            public void pushClip(int x1, int y1, int x2, int y2) {
                graphics.enableScissor(x1, y1, x2, y2);
            }

            @Override
            public void popClip() {
                graphics.disableScissor();
            }
        };
    }
    //?}
}
