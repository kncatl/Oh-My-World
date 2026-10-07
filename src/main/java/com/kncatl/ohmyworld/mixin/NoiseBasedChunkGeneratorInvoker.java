package com.kncatl.ohmyworld.mixin;

import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
//? >=26.3 {
import net.minecraft.world.level.levelgen.material.rule.MaterialRule;
//?}

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * 26.3 把雕刻并进 {@code buildTerrain}、没有公开的雕刻入口；用 {@code @Invoker}
 * 借出私有的 {@code createNoiseChunk} / {@code generateCarvers}，让
 * 「公式接管 + {@code [carvers:vanilla]}」在 26.3 上继续复用原版雕刻逻辑：
 * 超平坦世界走"干燥代理生成器"（见 {@code FlatCarvers}），
 * 噪声维度（下界/末地）直接在生成器自身设置上跑。
 *
 * <p>这两个方法只存在于 26.3（stonecutter 按版本保留）；更早版本的雕刻走
 * {@code applyCarvers}，不需要本接口（此时接口为空、mixin 无副作用）。
 */
@Mixin(NoiseBasedChunkGenerator.class)
public interface NoiseBasedChunkGeneratorInvoker {

    //? >=26.3 {
    /** 借 {@code NoiseBasedChunkGenerator.createNoiseChunk}（含结构 Beardifier 与含水层）。 */
    @Invoker("createNoiseChunk")
    NoiseChunk ohmyworld$createNoiseChunk(ChunkAccess chunk, StructureManager structureManager,
                                          Blender blender, RandomState randomState, NoiseSettings noiseSettings);

    /** 借 {@code NoiseBasedChunkGenerator.generateCarvers}（17×17 邻域 + 雕刻掩码后处理）。 */
    @Invoker("generateCarvers")
    void ohmyworld$generateCarvers(ChunkAccess chunk, Blender blender, NoiseChunk noiseChunk,
                                   RandomState randomState, BiomeManager biomeManager,
                                   WorldGenRegion carverBiomeRegion, MaterialRule materialRule);
    //?}
}
