package com.kncatl.ohmyworld.platform.fabric;

//? if FABRIC {
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;

import com.kncatl.ohmyworld.client.FlatPatternScreenEvents;

public class OhMyWorldFabricClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        // 通过 AFTER_INIT 注册 per-screen 的 afterRender 回调，检查创建世界预设状态
        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) ->
                ScreenEvents.afterRender(screen).register((screen1, graphics, mouseX, mouseY, partialTick) ->
                        FlatPatternScreenEvents.onScreenRenderPost(screen1)));
    }
}
//?}
