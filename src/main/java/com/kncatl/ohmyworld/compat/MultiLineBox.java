package com.kncatl.ohmyworld.compat;

import java.lang.reflect.Method;
import java.util.function.Consumer;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;
//? >=1.21.11 {
import net.minecraft.client.gui.components.MultiLineEditBox;
//?}

/**
 * 多行公式编辑框的版本适配工厂。
 *
 * <p>构造 API 在 1.21.x 中途换过：1.21.5 及以前是公开构造
 * {@code (Font, x, y, w, h, placeholder, message)}；1.21.11 起改为
 * {@code MultiLineEditBox.builder()}。而 1.21.5 构建的 jar 要覆盖
 * 1.21.6–1.21.10（变换发生在区间内的哪个版本不可考），因此那一支用反射
 * 同时探测两种构造方式；1.21.11+ 分支直接编译期调用（由编译保证 API 正确）。
 * 读写方法（setValue/getValue/setCharacterLimit/setValueListener）两代同名。
 */
public final class MultiLineBox {

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
        MultiLineEditBox box = MultiLineEditBox.builder()
                .setX(x).setY(y)
                .setPlaceholder(placeholder)
                .build(font, width, height, narration);
        box.setCharacterLimit(maxLength);
        box.setValueListener(responder);
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
        //?} else {
        return createReflective(font, x, y, width, height, placeholder, narration, maxLength, responder);
        //?}
    }

    /** 1.21.5 节点用：反射探测 builder / 公开构造两种形态（覆盖 1.21.5–1.21.10）。 */
    private static Handle createReflective(Font font, int x, int y, int width, int height,
                                           Component placeholder, Component narration, int maxLength,
                                           Consumer<String> responder) {
        try {
            Class<?> boxClass = Class.forName("net.minecraft.client.gui.components.MultiLineEditBox");
            Object box = null;
            try {
                Object builder = boxClass.getMethod("builder").invoke(null);
                builder.getClass().getMethod("setX", int.class).invoke(builder, x);
                builder.getClass().getMethod("setY", int.class).invoke(builder, y);
                builder.getClass().getMethod("setPlaceholder", Component.class).invoke(builder, placeholder);
                box = builder.getClass()
                        .getMethod("build", Font.class, int.class, int.class, Component.class)
                        .invoke(builder, font, width, height, narration);
            } catch (NoSuchMethodException notBuilderEra) {
                box = boxClass.getConstructor(Font.class, int.class, int.class, int.class, int.class,
                                Component.class, Component.class)
                        .newInstance(font, x, y, width, height, placeholder, narration);
            }
            final Object widget = box;
            final Method setValue = boxClass.getMethod("setValue", String.class);
            final Method getValue = boxClass.getMethod("getValue");
            final Method setListener = boxClass.getMethod("setValueListener", Consumer.class);
            boxClass.getMethod("setCharacterLimit", int.class).invoke(widget, maxLength);
            setListener.invoke(widget, responder);
            return new Handle() {
                @Override
                public AbstractWidget widget() {
                    return (AbstractWidget) widget;
                }

                @Override
                public String value() {
                    try {
                        return (String) getValue.invoke(widget);
                    } catch (Exception e) {
                        return "";
                    }
                }

                @Override
                public void setValue(String value) {
                    try {
                        setValue.invoke(widget, value);
                    } catch (Exception ignored) {}
                }

                @Override
                public void setResponder(Consumer<String> newResponder) {
                    try {
                        setListener.invoke(widget, newResponder);
                    } catch (Exception ignored) {}
                }
            };
        } catch (Exception e) {
            throw new IllegalStateException("MultiLineEditBox is not constructible on this version", e);
        }
    }
}
