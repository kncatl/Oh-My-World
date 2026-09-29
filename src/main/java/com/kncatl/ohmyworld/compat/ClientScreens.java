package com.kncatl.ohmyworld.compat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/**
 * 切换屏幕的版本差异收敛层。
 *
 * <p>26.3 起 {@code Minecraft.setScreen} 被移除，只剩
 * {@code setScreenAndShow}；26.1/26.1.2 两个方法都在，更早版本只有
 * {@code setScreen}。调用方只写「切到哪个屏幕」，不关心方法名。
 */
public final class ClientScreens {

    private ClientScreens() {}

    /** 打开/切换当前屏幕。 */
    public static void show(Minecraft mc, Screen screen) {
        //? >=26.3 {
        mc.setScreenAndShow(screen);
        //?} else {
        mc.setScreen(screen);
        //?}
    }
}
