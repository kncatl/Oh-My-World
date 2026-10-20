package com.kncatl.ohmyworld.mixin;

//? <26.3 {
import java.util.function.Function;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.CarvingMask;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.carver.CarverConfiguration;
import net.minecraft.world.level.levelgen.carver.CarvingContext;
import org.apache.commons.lang3.mutable.MutableBoolean;
//?}
import net.minecraft.world.level.levelgen.carver.WorldCarver;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.kncatl.ohmyworld.CarverFluidGuard;

/**
 * {@code [carvers:vanilla-ew]}（except water；&lt;26.3）：放行原版雕刻，但跳过
 * **流体方块本身**（水与岩浆）——水/岩浆不会被替换成空腔（水中的洞穴/峡谷消失、
 * 下界岩浆池不被掏空），流体下方的固体（海床等）照常雕刻。洞穴 / 峡谷 carver
 * 都走基类 {@link WorldCarver#carveBlock}；下界 carver 自写 carveBlock，
 * 由 {@link MixinNetherWorldCarver} 单独处理。
 *
 * <p>仅当保护激活（正在雕刻 [carvers:vanilla-ew] 的公式区块）且该点当前是水/岩浆时
 * 取消（返回 false = 按"未雕刻"处理）。未激活时零行为变化——原版维度不受影响。
 */
@Mixin(WorldCarver.class)
public class MixinWorldCarver {

    //? <26.3 {
    @Inject(method = "carveBlock", at = @At("HEAD"), cancellable = true)
    private void ohmyworld$skipFluid(CarvingContext context, CarverConfiguration config, ChunkAccess chunk,
                                     Function<BlockPos, Holder<Biome>> biomeGetter, CarvingMask mask,
                                     BlockPos.MutableBlockPos pos, BlockPos.MutableBlockPos checkPos,
                                     Aquifer aquifer, MutableBoolean reachedSurface,
                                     CallbackInfoReturnable<Boolean> cir) {
        if (CarverFluidGuard.skipFluid(chunk, pos)) {
            cir.setReturnValue(false);
        }
    }
    //?}
}
