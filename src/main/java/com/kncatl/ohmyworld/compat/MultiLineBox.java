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

        /** 只读显示模式（滚动/选区/复制可用，禁止编辑）。 */
        void setReadOnly(boolean readOnly);

        /** 是否按公式语法着色（错误/状态信息关闭）。 */
        void setSyntaxHighlight(boolean enabled);
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

            @Override
            public void setReadOnly(boolean readOnly) {
                editor.setReadOnly(readOnly);
            }

            @Override
            public void setSyntaxHighlight(boolean enabled) {
                editor.setSyntaxHighlight(enabled);
            }
        };
    }

    /** 只读多行显示框：可滚动、框选、Ctrl+A/C，不显示语法高亮与光标。 */
    public static Handle createReadOnly(Font font, int x, int y, int width, int height,
                                        Component placeholder, Component narration, int maxLength) {
        Handle handle = create(font, x, y, width, height, placeholder, narration, maxLength, null);
        handle.setReadOnly(true);
        handle.setSyntaxHighlight(false);
        return handle;
    }
}
