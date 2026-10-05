package com.kncatl.ohmyworld.mixin;

import java.util.function.Function;

import net.minecraft.client.gui.screens.worldselection.WorldOpenFlows;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.level.levelgen.WorldDimensions;

import com.kncatl.ohmyworld.PatternData;
import com.kncatl.ohmyworld.client.VanillaOverworld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 客户端世界创建/加载信号：
 * - createFreshLevel 仅在“确认创建新世界”后调用，用于把创建世界界面残留的 pending
 *   状态固化为本次创建确实发生（新建的世界一定是 flat_plus，因为 pending 只在
 *   flat_plus 被选中时置位）；同时按公式决定是否把主世界生成器换成原版（方案 A）。
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

    //? >=1.21.2 {
    /** 公式没有 overworld 节时，包装创建用的维度函数：主世界换成原版噪声生成器。 */
    @ModifyVariable(method = "createFreshLevel", at = @At("HEAD"), argsOnly = true)
    private Function<HolderLookup.Provider, WorldDimensions> ohmyworld$replaceOverworld(
            Function<HolderLookup.Provider, WorldDimensions> dimensions) {
        return VanillaOverworld.wrap(dimensions);
    }
    //?} else {
    /** 1.21.1 的维度函数参数 / 替换 API 使用 RegistryAccess。 */
    @ModifyVariable(method = "createFreshLevel", at = @At("HEAD"), argsOnly = true)
    private Function<RegistryAccess, WorldDimensions> ohmyworld$replaceOverworld(
            Function<RegistryAccess, WorldDimensions> dimensions) {
        return VanillaOverworld.wrap(dimensions);
    }
    //?}

    @Inject(method = "openWorld", at = @At("HEAD"))
    private void ohmyworld$onOpenWorld(String levelName, Runnable onFinish, CallbackInfo ci) {
        PatternData.clearPending();
        PatternData.clearCreated();
    }
}
