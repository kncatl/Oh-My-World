package com.kncatl.ohmyworld.mixin;

import java.util.List;

import net.minecraft.core.Holder;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.structure.StructureSet;

import com.kncatl.ohmyworld.PatternData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 结构控制与装饰特性开关：{@code [structure:...]} 指令在生成期裁剪结构组；
 * {@code [features:none]} 关闭该维度的装饰特性（树、矿、荧石等）。
 *
 * <p>结构生成入口 {@code createStructures} 迭代的是
 * {@code ChunkGeneratorStructureState.possibleStructureSets()}（1.21.1–26.3 结构一致，
 * 签名与调用点已核对）。这里把它替换成按“该生成器所属维度”的规则过滤后的列表：
 * 未绑定生成器（非本模组世界）/ 旧公式（无指令）/ 规则=all 时原样返回，零行为变化。
 *
 * <p>{@code applyBiomeDecoration} 前半段负责放置结构部件、后半段跑装饰特性循环。
 * 特性循环迭代的是 {@code featuresPerStep.get()}（该方法内唯一的 Supplier.get 调用点，
 * 1.21.1–26.3 字节码已核对）；{@code [features:none]} 时返回空列表即可只掐特性、
 * 不影响结构部件放置。换群系后的特性表重建在 {@code BiomeControl} 里完成。
 *
 * <p>locate 与末影之眼不受影响（仍按理论位置计算；指南已注明）。
 */
@Mixin(ChunkGenerator.class)
public class MixinChunkGenerator {

    @Redirect(method = "createStructures", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/chunk/ChunkGeneratorStructureState;possibleStructureSets()"
                    + "Ljava/util/List;"))
    private List<Holder<StructureSet>> ohmyworld$filterStructureSets(ChunkGeneratorStructureState state) {
        return PatternData.filterStructureSets((ChunkGenerator) (Object) this, state.possibleStructureSets());
    }

    @Redirect(method = "applyBiomeDecoration", at = @At(value = "INVOKE",
            target = "Ljava/util/function/Supplier;get()Ljava/lang/Object;"))
    private Object ohmyworld$suppressFeatures(java.util.function.Supplier<?> supplier) {
        if (PatternData.suppressFeatures((ChunkGenerator) (Object) this)) return List.of();
        return supplier.get();
    }
}
