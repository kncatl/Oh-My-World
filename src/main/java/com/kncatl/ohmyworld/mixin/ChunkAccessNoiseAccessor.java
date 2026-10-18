package com.kncatl.ohmyworld.mixin;

import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.NoiseChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * &lt;26.3 的 {@code applyCarvers} 会复用区块里由原版生成阶段缓存的
 * {@code NoiseChunk}（含原版含水层）。{@code [carvers:vanilla-ew]} 的干燥代理
 * 需要临时清空该缓存，让代理按干燥设置新建 NoiseChunk；雕刻后恢复原值。
 *
 * <p>26.3 没有这个字段（雕刻走 {@code generateCarvers} 显式传入 NoiseChunk），
 * 由 mixin 插件跳过本类。
 */
@Mixin(ChunkAccess.class)
public interface ChunkAccessNoiseAccessor {

    @Accessor("noiseChunk")
    NoiseChunk ohmyworld$getNoiseChunk();

    @Accessor("noiseChunk")
    void ohmyworld$setNoiseChunk(NoiseChunk noiseChunk);
}
