package com.kncatl.ohmyworld.compat;

import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
//? >=26.1 {
import net.minecraft.client.gui.GuiGraphicsExtractor;
//?} else {
import net.minecraft.client.gui.GuiGraphics;
//?}

/**
 * 屏幕绘制的最小通用接口（只覆盖本模组用到的那几个绘制能力）。
 *
 * <p>26.1 起 MC 的渲染改成「先提取渲染状态」模型：{@code GuiGraphics} 被
 * {@code GuiGraphicsExtractor} 取代，{@code Screen.render(...)} 也换成了
 * {@code extractRenderState(...)}。屏幕代码只依赖这里的 {@link Text}，
 * 版本差异全部留在本文件里。
 */
public final class GuiCompat {

    /** 版本无关的文字绘制能力。 */
    public interface Text {
        void text(Font font, String value, int x, int y, int color);

        void text(Font font, Component value, int x, int y, int color);

        void centered(Font font, Component value, int x, int y, int color);
    }

    private GuiCompat() {}

    //? >=26.1 {
    public static Text of(GuiGraphicsExtractor graphics) {
        return new Text() {
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
        };
    }
    //?} else {
    public static Text of(GuiGraphics graphics) {
        return new Text() {
            @Override
            public void text(Font font, String value, int x, int y, int color) {
                graphics.drawString(font, value, x, y, color);
            }

            @Override
            public void text(Font font, Component value, int x, int y, int color) {
                graphics.drawString(font, value, x, y, color);
            }

            @Override
            public void centered(Font font, Component value, int x, int y, int color) {
                graphics.drawCenteredString(font, value, x, y, color);
            }
        };
    }
    //?}
}
