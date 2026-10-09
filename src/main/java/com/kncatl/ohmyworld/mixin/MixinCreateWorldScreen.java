package com.kncatl.ohmyworld.mixin;

import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationContext;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;

import com.kncatl.ohmyworld.client.VanillaOverworld;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 「公式世界的主世界需要原版噪声生成器时（没有 overworld 节，或 overworld 节
 * 使用 [terrain:vanilla] 叠加模式），新建世界的主世界换成原版生成」的注入点。
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
 *
 * <p>用 MixinExtras 的 {@code @WrapOperation} 而非 Sponge 的 {@code @Redirect}：
 * 旧 Fabric Loader（0.18.x，MixinExtras 0.5.0）读取数组形态的 {@code @Redirect.at}
 * 会崩；{@code @WrapOperation} 的 {@code at} 在两代 MixinExtras 里都是数组形态。
 */
@Mixin(CreateWorldScreen.class)
public class MixinCreateWorldScreen {

    @WrapOperation(method = "onCreate", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/screens/worldselection/WorldCreationUiState;getSettings()"
                    + "Lnet/minecraft/client/gui/screens/worldselection/WorldCreationContext;"))
    private WorldCreationContext ohmyworld$replaceOverworldOnCreate(WorldCreationUiState uiState,
                                                                    Operation<WorldCreationContext> original) {
        return VanillaOverworld.maybeReplace(original.call(uiState));
    }
}
