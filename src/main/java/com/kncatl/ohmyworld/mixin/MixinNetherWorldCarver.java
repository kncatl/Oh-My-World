package com.kncatl.ohmyworld.mixin;

import net.minecraft.world.level.chunk.CarvingMask;
import org.spongepowered.asm.mixin.Mixin;

//? <26.3 {
import java.util.function.Function;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.carver.CarvingContext;
import net.minecraft.world.level.levelgen.carver.CaveCarverConfiguration;
import net.minecraft.world.level.levelgen.carver.NetherWorldCarver;
import org.apache.commons.lang3.mutable.MutableBoolean;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.kncatl.ohmyworld.CarverFluidGuard;

/**
 * {@code [carvers:vanilla-ew]}（&lt;26.3 的下界）：{@link NetherWorldCarver} 自己
 * 重写了 {@code carveBlock}，不经过 {@link MixinWorldCarver} 在
 * {@code WorldCarver#carveBlock} 上的注入；下界又恰好有大量岩浆，必须单独拦截。
 *
 * <p>与水同样处理：当前方块是水/岩浆时拒绝写入（返回 false = 未雕刻），
 * 流体下方的岩石照常雕刻。未激活保护时零行为变化。
 *
 * <p>26.3 起雕刻候选统一写入 {@link CarvingMask}，由 {@link MixinCarvingMask}
 * 覆盖（本 mixin 在 26.3 不存在目标类，由 {@code OhMyWorldMixinPlugin} 跳过）。
 */
@Mixin(NetherWorldCarver.class)
public class MixinNetherWorldCarver {

    @Inject(method = "carveBlock", at = @At("HEAD"), cancellable = true)
    private void ohmyworld$skipFluid(CarvingContext context, CaveCarverConfiguration config, ChunkAccess chunk,
                                     Function<BlockPos, Holder<Biome>> biomeGetter, CarvingMask mask,
                                     BlockPos.MutableBlockPos pos, BlockPos.MutableBlockPos checkPos,
                                     Aquifer aquifer, MutableBoolean reachedSurface,
                                     CallbackInfoReturnable<Boolean> cir) {
        if (CarverFluidGuard.skipFluid(chunk, pos)) {
            cir.setReturnValue(false);
        }
    }
}
//?} else {
/** 26.3：{@code NetherWorldCarver} 已不存在（雕刻统一走 CarvingMask），保留一个
 *  无注入的占位 mixin 供配置加载；{@code OhMyWorldMixinPlugin} 会跳过它。 */
@Mixin(CarvingMask.class)
public class MixinNetherWorldCarver {
}
//?}
