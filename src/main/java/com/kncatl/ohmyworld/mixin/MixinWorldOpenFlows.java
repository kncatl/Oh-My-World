package com.kncatl.ohmyworld.mixin;

import net.minecraft.client.gui.screens.worldselection.WorldOpenFlows;

import com.kncatl.ohmyworld.PatternData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 客户端世界创建/加载信号：
 * - createFreshLevel 仅被主菜单的快速创建路径调用：把创建世界界面残留的 pending
 *   状态固化为“本次创建确实发生”。（创建世界界面本身的流程走 onCreate，
 *   见 {@link MixinCreateWorldScreen}。）
 * - openWorld 仅在从主菜单加载已有世界时调用，此时任何 pending/created 都是残留，
 *   一律清除，避免污染被加载的世界。
 */
@Mixin(WorldOpenFlows.class)
public class MixinWorldOpenFlows {

    @Inject(method = "createFreshLevel", at = @At("HEAD"))
    private void ohmyworld$onCreateFreshLevel(CallbackInfo ci) {
        if (PatternData.isPending()) {
            PatternData.setCreated();
        }
    }

    @Inject(method = "openWorld", at = @At("HEAD"))
    private void ohmyworld$onOpenWorld(String levelName, Runnable onFinish, CallbackInfo ci) {
        PatternData.clearPending();
        PatternData.clearCreated();
    }
}
