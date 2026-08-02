package com.kncatl.ohmyworld.mixin;

import net.minecraft.client.gui.screens.worldselection.PresetEditor;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.Holder;
import net.minecraft.world.level.levelgen.presets.WorldPreset;

import com.kncatl.ohmyworld.client.CustomFlatScreen;
import com.kncatl.ohmyworld.client.FlatPatternScreenEvents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

//? if FABRIC {
/**
 * Fabric 端没有 RegisterPresetEditorsEvent 之类的事件，无法像 NeoForge 那样把
 * 预设编辑器注册到 PresetEditor.EDITORS（该 map 是不可变的 Map.of 构造）。
 * 这里在 getPresetEditor() 返回时兜底：若当前选中的世界类型是
 * ohmyworld:flat_plus 且原版查询不到编辑器，则返回 CustomFlatScreen 编辑器，
 * 使创建世界界面上的“自定义”按钮激活并可打开公式编辑器。
 */
@Mixin(WorldCreationUiState.class)
public class MixinWorldCreationUiState {

    @Inject(method = "getPresetEditor", at = @At("RETURN"), cancellable = true)
    private void ohmyworld$onGetPresetEditor(CallbackInfoReturnable<PresetEditor> cir) {
        if (cir.getReturnValue() != null) return;
        WorldCreationUiState state = (WorldCreationUiState) (Object) this;
        var holder = state.getWorldType();
        if (holder == null) return;
        Holder<WorldPreset> preset = holder.preset();
        if (preset == null) return;
        //? >=1.21.11 {
        if (preset.unwrapKey().map(k -> k.identifier().equals(FlatPatternScreenEvents.OUR_KEY.identifier())).orElse(false)) {
        //?} else {
        if (preset.unwrapKey().map(k -> k.location().equals(FlatPatternScreenEvents.OUR_KEY.location())).orElse(false)) {
        //?}
            cir.setReturnValue(CustomFlatScreen::new);
        }
    }
}
//?}