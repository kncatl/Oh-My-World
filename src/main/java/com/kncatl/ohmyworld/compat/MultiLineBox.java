package com.kncatl.ohmyworld.compat;

import java.util.List;
import java.util.function.Consumer;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import com.kncatl.ohmyworld.client.FormulaEditor;

/**
 * 多行公式编辑框的工厂。
 *
 * <p>返回带语法高亮的自绘编辑器 {@link FormulaEditor}：文本模型使用原版的
 * {@code MultilineTextField}，因此换行、选区、剪贴板、词级跳转等编辑行为与原版
 * 多行框一致；绘制由本模组接管，从而能按公式语法着色（原版多行框只能整段单色）。
 */
public final class MultiLineBox {

    /** 版本无关的多行框句柄。 */
    public interface Handle {
        AbstractWidget widget();

        String value();

        void setValue(String value);

        void setResponder(Consumer<String> responder);

        /** 标注错误区间（原文坐标），用于「可能出错的位置」提示。 */
        void setErrorSpans(List<int[]> spans);
    }

    private MultiLineBox() {}

    public static Handle create(Font font, int x, int y, int width, int height,
                                Component placeholder, Component narration, int maxLength,
                                Consumer<String> responder) {
        FormulaEditor editor = new FormulaEditor(font, x, y, width, height,
                placeholder, narration, maxLength, responder);
        return new Handle() {
            @Override
            public AbstractWidget widget() {
                return editor;
            }

            @Override
            public String value() {
                return editor.value();
            }

            @Override
            public void setValue(String value) {
                editor.setValue(value);
            }

            @Override
            public void setResponder(Consumer<String> newResponder) {
                editor.setResponder(newResponder);
            }

            @Override
            public void setErrorSpans(List<int[]> spans) {
                editor.setErrorSpans(spans);
            }
        };
    }
}
