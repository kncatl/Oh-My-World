package com.kncatl.ohmyworld.mixin;

import java.util.Optional;

import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.structure.Structure;

import com.kncatl.ohmyworld.OhMyWorldConfig;
import com.kncatl.ohmyworld.PatternData;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 水面上不生成地表结构（1.3.4，用户拍板：跳过）。
 *
 * <p>公式世界里群系与地形可以不一致（例如默认平原 + 公式海洋），原版的
 * {@code WORLD_SURFACE_WG} 高度包含水面，于是村庄/神庙/冰屋等会被放到水面上。
 * 这里在结构起点确定之后（{@link Structure#findValidGenerationPoint} 的 RETURN）
 * 做一次水面判定：
 * <ul>
 *   <li>只处理 {@code step = SURFACE_STRUCTURES}；海底结构与地下结构天然不在水面高度；</li>
 *   <li>该列必须有水体，且起点 y 贴着水面（±1，覆盖 getFirstFreeHeight /
 *       getFirstOccupiedHeight 两种起点算法）；</li>
 *   <li>该列最高非空气方块必须是<b>水</b>——下界的熔岩海不触发。</li>
 * </ul>
 *
 * <p>判定按生成器当前生效的列计算：非叠加模式 = 公式列；叠加模式 = 叠加后的列
 * （见 {@link PatternData#isSurfaceOnWater}）。未绑定公式的世界零影响。
 */
@Mixin(Structure.class)
public class MixinStructure {
    private static final Logger LOGGER = LogUtils.getLogger();

    @Inject(method = "findValidGenerationPoint", at = @At("RETURN"), cancellable = true)
    private void ohmyworld$skipSurfaceStructureOnWater(Structure.GenerationContext context,
                                                       CallbackInfoReturnable<Optional<Structure.GenerationStub>> cir) {
        Optional<Structure.GenerationStub> stub = cir.getReturnValue();
        if (stub == null || stub.isEmpty()) return;
        // 海底/地下结构不受影响（沉船、海底废墟、矿井、要塞、远古城市…）。
        Structure self = (Structure) (Object) this;
        if (self.step() != GenerationStep.Decoration.SURFACE_STRUCTURES) return;
        ChunkGenerator generator = context.chunkGenerator();
        PatternData.PatternSnapshot snapshot = PatternData.snapshotFor(generator);
        if (snapshot == null) return;
        if (PatternData.isSurfaceOnWater(snapshot, generator, stub.get().position(),
                context.heightAccessor(), context.randomState())) {
            if (OhMyWorldConfig.debugLogsEnabled()) {
                LOGGER.info("ohmyworld: skipped surface structure {} at {} (water surface)",
                        self.getClass().getSimpleName(), stub.get().position());
            }
            cir.setReturnValue(Optional.empty());
        }
    }
}
