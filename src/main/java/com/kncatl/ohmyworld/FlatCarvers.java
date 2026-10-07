package com.kncatl.ohmyworld;

import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import com.kncatl.ohmyworld.compat.ResourceIds;
import com.kncatl.ohmyworld.mixin.NoiseBasedChunkGeneratorInvoker;
import com.mojang.logging.LogUtils;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import org.slf4j.Logger;

/**
 * 超平坦世界的"原版雕刻"支持：{@code FlatLevelSource} 的雕刻入口是空实现
 * （&lt;26.3 的 {@code applyCarvers} / 26.3 的 {@code buildTerrain}），
 * 因此在超平坦世界里写 {@code [carvers:vanilla]} 原本不会有洞。
 *
 * <p>做法（代理）：用同一群系源 + 该维度的**原版噪声设置**造一个临时
 * {@link NoiseBasedChunkGenerator}，把原版雕刻逻辑原样借来执行——
 * 17×17 邻域、生物群系雕刻配置、洞穴形状全部复用原版实现。设置用一份"干燥副本"：
 * {@code aquifersEnabled=false}（不灌含水层）+ {@code defaultFluid=air}
 * （含水层关闭后原版仍会把"流体选择器"的水填进空腔，所以默认流体也要改成空气），
 * 于是雕出的洞穴只留空气，y=-54 以下仍为岩浆（与原版雕刻的下限一致）。
 *
 * <p>版本分支：
 * <ul>
 *   <li>&lt;26.3：借代理的 {@code applyCarvers}（由 {@code MixinFlatLevelSource}
 *       在 {@code applyCarvers} 的 HEAD 注入里调用）；</li>
 *   <li>26.3：雕刻并入 {@code buildTerrain}、没有 {@code applyCarvers}；
 *       改用 {@code @Invoker} 借原版私有的 {@code createNoiseChunk} +
 *       {@code generateCarvers}（在 {@code buildTerrain} 注入、公式填充之后调用）。</li>
 * </ul>
 *
 * <p>缓存：按平坦生成器缓存随机状态与干燥设置（随机状态构建代价高；缓存值不引用
 * 生成器本身，WeakHashMap 可随生成器正常回收）；代理生成器每次现构造（轻量），
 * 这样热重载换过群系源后也会用上最新的群系源。
 */
public final class FlatCarvers {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final AtomicBoolean WARNED = new AtomicBoolean();

    private FlatCarvers() {}

    /**
     * &lt;26.3 入口：在公式地形上按原版逻辑雕刻（仅当调用方已确认
     * {@code [carvers:vanilla]}）。失败只记一次日志、不影响区块生成。
     */
    public static void carve(ChunkGenerator flat, WorldGenRegion region, long seed,
                             BiomeManager biomeManager, StructureManager structureManager, ChunkAccess chunk) {
        //? <26.3 {
        try {
            Cached cached = cachedFor(flat, region.getLevel());
            NoiseBasedChunkGenerator delegate = new NoiseBasedChunkGenerator(
                    flat.getBiomeSource(), cached.dry());
            applyCarvers(delegate, region, seed, cached.randomState(), biomeManager, structureManager, chunk);
        } catch (Exception e) {
            warn(region, e);
        }
        //?}
        // 26.3：雕刻并入 buildTerrain，入口是 carveModern。
    }

    //? >=26.3 {
    /**
     * 26.3 入口：在 {@code buildTerrain} 注入里、公式填充之后调用；借原版私有的
     * {@code createNoiseChunk} + {@code generateCarvers}（干燥代理生成器）跑同一套雕刻。
     */
    public static void carveModern(ChunkGenerator flat, WorldGenRegion region, Blender blender,
                                   BiomeManager biomeManager, StructureManager structureManager, ChunkAccess chunk) {
        try {
            Cached cached = cachedFor(flat, region.getLevel());
            NoiseGeneratorSettings dry = cached.dry().value();
            NoiseBasedChunkGenerator delegate = new NoiseBasedChunkGenerator(
                    flat.getBiomeSource(), cached.dry());
            NoiseBasedChunkGeneratorInvoker invoker = (NoiseBasedChunkGeneratorInvoker) (Object) delegate;
            NoiseSettings noiseSettings = dry.noiseSettings()
                    .clampToHeightAccessor(chunk.getHeightAccessorForGeneration());
            try (NoiseChunk noiseChunk = invoker.ohmyworld$createNoiseChunk(
                    chunk, structureManager, blender, cached.randomState(), noiseSettings)) {
                invoker.ohmyworld$generateCarvers(chunk, blender, noiseChunk, cached.randomState(),
                        biomeManager, region, dry.materialRule().value());
            }
        } catch (Exception e) {
            warn(region, e);
        }
    }
    //?}

    private static void warn(WorldGenRegion region, Exception e) {
        if (WARNED.compareAndSet(false, true)) {
            LOGGER.error("ohmyworld: flat-world carvers failed for {}: {}",
                    ResourceIds.keyIdString(region.getLevel().dimension()), e.toString());
        }
    }

    private record Cached(RandomState randomState, Holder<NoiseGeneratorSettings> dry) {}

    private static final Map<ChunkGenerator, Cached> CACHE = new WeakHashMap<>();

    private static Cached cachedFor(ChunkGenerator flat, ServerLevel level) {
        synchronized (CACHE) {
            Cached cached = CACHE.get(flat);
            if (cached != null) return cached;
            NoiseGeneratorSettings settings = BiomeControl.vanillaNoiseSettings(level);
            // "干燥副本"：关含水层 + 默认流体改空气（含水层关闭后 createDisabled 会把
            // 流体选择器的水填进空腔，因此仅关含水层不够，默认流体必须一并改掉）。
            //? >=26.3 {
            NoiseGeneratorSettings dry = new NoiseGeneratorSettings(
                    settings.noiseSettings(), settings.defaultBlock(), Blocks.AIR.defaultBlockState(),
                    settings.noiseRouter(), settings.materialRule(), settings.spawnTarget(),
                    settings.seaLevel(), settings.disableMobGeneration(), Optional.empty(),
                    settings.useLegacyRandomSource(), settings.debugFunctions());
            //?} else {
            NoiseGeneratorSettings dry = new NoiseGeneratorSettings(
                    settings.noiseSettings(), settings.defaultBlock(), Blocks.AIR.defaultBlockState(),
                    settings.noiseRouter(), settings.surfaceRule(), settings.spawnTarget(),
                    settings.seaLevel(), settings.disableMobGeneration(),
                    false, settings.oreVeinsEnabled(), settings.useLegacyRandomSource());
            //?}
            RandomState randomState = BiomeControl.createRandomState(level, settings);
            Cached created = new Cached(randomState, Holder.direct(dry));
            CACHE.put(flat, created);
            return created;
        }
    }

    //? <26.3 {
    private static void applyCarvers(NoiseBasedChunkGenerator generator, WorldGenRegion region, long seed,
                                     RandomState randomState, BiomeManager biomeManager,
                                     StructureManager structureManager, ChunkAccess chunk) {
        //? >=1.21.2 {
        generator.applyCarvers(region, seed, randomState, biomeManager, structureManager, chunk);
        //?} else {
        generator.applyCarvers(region, seed, randomState, biomeManager, structureManager, chunk,
                GenerationStep.Carving.AIR);
        //?}
    }
    //?}
}
