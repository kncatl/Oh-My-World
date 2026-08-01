package com.kncatl.ohmyworld.platform;

import net.minecraft.client.gui.screens.Screen;

@FunctionalInterface
public interface ScreenRenderCallback {
    void onRender(Screen screen);
}