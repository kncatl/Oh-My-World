package com.kncatl.ohmyworld.compat;

import java.util.function.Consumer;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.network.chat.Component;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

/**
 * 多行公式编辑框的版本适配工厂。
 *
 * <p>构造 API 分界：1.21.5 及以前是公开构造
 * {@code (Font, x, y, w, h, placeholder, message)}；1.21.11 起改为
 * {@code MultiLineEditBox.builder()}。两代都用<strong>编译期调用</strong>
 * （Loom/Stonecutter 按节点重映射到目标版本的名称）。
 *
 * <p>为什么不用反射：Fabric 运行时是 intermediary（{@code net.minecraft.class_XXXX} /
 * {@code method_XXXX}），反射 mojmap 名称必然失败——这正是 1.21.4-fabric 打开编辑器
 * 崩溃的根因（此前反射路径只在 NeoForge 的 mojmap 运行时下能成功）。
 *
 * <p>1.21.5 节点的 jar 还要覆盖 1.21.6–1.21.10（构造形态可能在区间内变化）：构造失败时
 * 退回单行 {@link EditBox}，任何环境下界面都能打开（编辑体验降级但不崩）。
 */
public final class MultiLineBox {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 版本无关的多行框句柄。 */
    public interface Handle {
        AbstractWidget widget();

        String value();

        void setValue(String value);

        void setResponder(Consumer<String> responder);
    }

    private MultiLineBox() {}

    public static Handle create(Font font, int x, int y, int width, int height,
                                Component placeholder, Component narration, int maxLength,
                                Consumer<String> responder) {
        //? >=1.21.11 {
        try {
            MultiLineEditBox box = MultiLineEditBox.builder()
                    .setX(x).setY(y)
                    .setPlaceholder(placeholder)
                    .build(font, width, height, narration);
            box.setCharacterLimit(maxLength);
            box.setValueListener(responder);
            return wrap(box);
        } catch (Throwable t) {
            LOGGER.warn("ohmyworld: MultiLineEditBox unavailable, falling back to a single-line field", t);
            return singleLine(font, x, y, width, height, narration, maxLength, responder);
        }
        //?} else {
        try {
            MultiLineEditBox box = new MultiLineEditBox(font, x, y, width, height, placeholder, narration);
            box.setCharacterLimit(maxLength);
            box.setValueListener(responder);
            return wrap(box);
        } catch (Throwable t) {
            LOGGER.warn("ohmyworld: MultiLineEditBox unavailable, falling back to a single-line field", t);
            return singleLine(font, x, y, width, height, narration, maxLength, responder);
        }
        //?}
    }

    private static Handle wrap(MultiLineEditBox box) {
        return new Handle() {
            @Override
            public AbstractWidget widget() {
                return box;
            }

            @Override
            public String value() {
                return box.getValue();
            }

            @Override
            public void setValue(String value) {
                box.setValue(value);
            }

            @Override
            public void setResponder(Consumer<String> newResponder) {
                box.setValueListener(newResponder);
            }
        };
    }

    private static Handle singleLine(Font font, int x, int y, int width, int height,
                                     Component narration, int maxLength, Consumer<String> responder) {
        EditBox box = new EditBox(font, x, y, width, height, narration);
        box.setMaxLength(maxLength);
        box.setResponder(responder);
        return new Handle() {
            @Override
            public AbstractWidget widget() {
                return box;
            }

            @Override
            public String value() {
                return box.getValue();
            }

            @Override
            public void setValue(String value) {
                box.setValue(value);
            }

            @Override
            public void setResponder(Consumer<String> newResponder) {
                box.setResponder(newResponder);
            }
        };
    }
}
