package com.kncatl.ohmyworld.platform.fabric;

//? if FABRIC {
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;

import com.kncatl.ohmyworld.client.FlatPatternScreenEvents;
import com.kncatl.ohmyworld.platform.Platform;

public class OhMyWorldFabricClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        // 通过 AFTER_INIT 注册 per-screen 的 afterRender 回调，转发到 Platform.onScreenRenderPost
        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) ->
                ScreenEvents.afterRender(screen).register((screen1, graphics, mouseX, mouseY, partialTick) ->
                        ((FabricPlatform) Platform.get()).dispatchScreenRender(screen1)));

        // 注册自定义预设编辑器（Fabric 无 RegisterPresetEditorsEvent，通过 mixin 处理）
        FlatPatternScreenEvents.register();
    }
}
//?}