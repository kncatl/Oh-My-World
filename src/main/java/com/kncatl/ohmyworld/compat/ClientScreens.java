package com.kncatl.ohmyworld.compat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/**
 * 切换屏幕的版本差异收敛层。
 *
 * <p>26.2 起 {@code Minecraft.setScreen} 被移除，只剩 {@code setScreenAndShow}；
 * 1.21.x 只有 {@code setScreen}。26.x 全版本（26.1/26.1.2/26.2/26.3）都有
 * {@code setScreenAndShow}，因此它是唯一能同时覆盖整个 26.x 目标区间的调用。
 */
public final class ClientScreens {

    private ClientScreens() {}

    /** 打开/切换当前屏幕。 */
    public static void show(Minecraft mc, Screen screen) {
        //? >=26.1 {
        mc.setScreenAndShow(screen);
        //?} else {
        mc.setScreen(screen);
        //?}
    }
}
