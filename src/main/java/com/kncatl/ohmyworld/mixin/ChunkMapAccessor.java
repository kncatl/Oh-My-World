package com.kncatl.ohmyworld.mixin;

import net.minecraft.server.level.ChunkMap;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.RandomState;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * {@code ChunkMap} 的两个私有字段访问器：
 * <ul>
 *   <li>{@code chunkGeneratorState}：群系源替换后重建结构组状态（新群系的专属结构才能解锁）；</li>
 *   <li>{@code randomState}：超平坦维度原本用 {@code NoiseGeneratorSettings.dummy()}
 *       构建（气候采样恒定，双噪声群系源会退化成单一群系）——换 {@code [biome:vanilla]}
 *       时替换成该维度真实噪声设置的 RandomState，群系分布才真实。</li>
 * </ul>
 * 字段名 1.21.1–26.3 一致。
 */
@Mixin(ChunkMap.class)
public interface ChunkMapAccessor {

    @Mutable
    @Accessor("chunkGeneratorState")
    void ohmyworld$setChunkGeneratorState(ChunkGeneratorStructureState state);

    @Mutable
    @Accessor("randomState")
    void ohmyworld$setRandomState(RandomState state);
}
