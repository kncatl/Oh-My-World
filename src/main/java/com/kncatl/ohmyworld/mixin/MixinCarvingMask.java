package com.kncatl.ohmyworld.mixin;

import net.minecraft.world.level.chunk.CarvingMask;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.kncatl.ohmyworld.CarverFluidGuard;

/**
 * {@code [carvers:vanilla-ew]}（except water；&ge;26.3）：雕刻候选以区块本地坐标
 * 写入 {@link CarvingMask} 后由生成器统一应用；这里在写入前拦截，把**流体方块
 * 本身**（水/岩浆）的候选丢弃（流体下方的固体照常雕刻）。未激活保护时零行为变化。
 */
@Mixin(CarvingMask.class)
public class MixinCarvingMask {

    //? >=26.3 {
    @Inject(method = "carve", at = @At("HEAD"), cancellable = true)
    private void ohmyworld$skipFluid(int x, int y, int z, CallbackInfo ci) {
        if (CarverFluidGuard.skipFluidLocal(x, y, z)) ci.cancel();
    }
    //?}
}
