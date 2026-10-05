package com.kncatl.ohmyworld.mixin;

import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationContext;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;

import com.kncatl.ohmyworld.client.VanillaOverworld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 「公式没有 overworld 节 → 新建世界的主世界用原版生成」的注入点。
 *
 * <p>创建世界界面在“创建”按钮/回车触发的 {@code onCreate} 里读取
 * {@code uiState.getSettings()}，随后用 {@code selectedDimensions().bake(...)}
 * 把维度固化进世界数据。这里把该 getSettings() 调用换成“按需替换过主世界生成器”的
 * 上下文——只影响这一次创建，编辑期间的世界类型显示不受影响。
 *
 * <p>为什么不用 {@code WorldOpenFlows.createFreshLevel}：该入口只被主菜单的快速
 * 创建路径调用；创建世界界面走的是 {@code onCreate → bake →
 * createLevelFromExistingSettings} 这条链（1.21.1 与 26.3 结构一致，NeoForge 的
 * 补丁也不修改 onCreate 区域）。
 */
@Mixin(CreateWorldScreen.class)
public class MixinCreateWorldScreen {

    @Redirect(method = "onCreate", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/screens/worldselection/WorldCreationUiState;getSettings()"
                    + "Lnet/minecraft/client/gui/screens/worldselection/WorldCreationContext;"))
    private WorldCreationContext ohmyworld$replaceOverworldOnCreate(WorldCreationUiState uiState) {
        return VanillaOverworld.maybeReplace(uiState.getSettings());
    }
}
