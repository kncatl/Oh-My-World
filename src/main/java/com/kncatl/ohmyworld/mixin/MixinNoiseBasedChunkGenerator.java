package com.kncatl.ohmyworld.mixin;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import net.minecraft.core.Holder;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;

import com.kncatl.ohmyworld.CarverWaterGuard;
import com.kncatl.ohmyworld.DimensionRules;
import com.kncatl.ohmyworld.OhMyWorldConfig;
import com.kncatl.ohmyworld.PatternData;
import com.kncatl.ohmyworld.compat.LevelHeights;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 噪声生成器（下界/末地等原版维度）的公式接管。
 *
 * <p>只有「该生成器实例被绑定过公式」时才生效（见 {@link PatternData#bindGenerator}）；
 * 未绑定的维度（包括非本模组世界的全部噪声维度）完全走原版路径。绑定表为空时
 * {@link PatternData#snapshotFor} 走零开销快路径。
 *
 * <p>接管时取消的事项：地形（{@code fillFromNoise}；26.3 起是合并了 surface 与
 * carvers 的 {@code buildTerrain}）、表面装饰（{@code buildSurface}，仅 26.3 前）、
 * 洞穴雕刻（{@code applyCarvers}，仅 26.3 前）。生物群系（{@code createBiomes}）
 * 不动——保留原版的下界/末地群系分布与特征/结构生成。
 *
 * <p>雕刻在 26.3 上并入 {@code buildTerrain}、没有公开入口：
 * {@code [carvers:vanilla]} 时用 {@link NoiseBasedChunkGeneratorInvoker}
 * 借原版私有的 {@code createNoiseChunk} + {@code generateCarvers} 补跑（见
 * {@code ohmyworld$onBuildTerrain}）。
 *
 * <p>版本分支说明：1.21.1 的 {@code applyCarvers} 比 1.21.2+ 多一个
 * {@code GenerationStep.Carving} 参数，因此该注入使用只声明 {@code CallbackInfo}
 * 的处理器（Mixin 允许省略目标方法的参数），与签名差异无关。
 */
@Mixin(NoiseBasedChunkGenerator.class)
public class MixinNoiseBasedChunkGenerator {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** 26.3 的雕刻若失败只提示一次（避免每区块刷屏）。 */
    private static final java.util.concurrent.atomic.AtomicBoolean CARVERS_ERROR_LOGGED =
            new java.util.concurrent.atomic.AtomicBoolean();
    /** 调试日志：applyCarvers 是否被调用/是否放行，只记录一次。 */
    private static final java.util.concurrent.atomic.AtomicBoolean CARVERS_LOGGED =
            new java.util.concurrent.atomic.AtomicBoolean();

    //? >=26.3 {
    // 26.3 把地形构建重做成单入口 buildTerrain：(fillFromNoise + buildSurface +
    // applyCarvers) 合并为这一处。
    @Inject(method = "buildTerrain", at = @At("HEAD"), cancellable = true)
    private void ohmyworld$onBuildTerrain(ChunkAccess chunk, Blender blender, RandomState randomState,
                                          StructureManager structureManager, BiomeManager biomeManager,
                                          WorldGenRegion region, Set<Holder<Biome>> biomes,
                                          CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        if (!ohmyworld$fillFromPattern(chunk, cir)) return;
        PatternData.PatternSnapshot snapshot = PatternData.snapshotFor((ChunkGenerator) (Object) this);
        if (snapshot == null || !snapshot.carversVanilla()) return;
        // 26.3 的雕刻并入本入口、没有公开入口：[carvers:vanilla] 时借原版私有的
        // createNoiseChunk + generateCarvers，在本生成器自身设置上补跑雕刻
        // （含水层行为与 1.21.x 的"放行原版 applyCarvers"一致）。
        // [carvers:vanilla-ew] 的“跳过水”保护在 generateCarvers 的注入里挂载。
        NoiseBasedChunkGenerator self = (NoiseBasedChunkGenerator) (Object) this;
        try {
            NoiseGeneratorSettings settings = self.generatorSettings().value();
            NoiseSettings noiseSettings = settings.noiseSettings()
                    .clampToHeightAccessor(chunk.getHeightAccessorForGeneration());
            NoiseBasedChunkGeneratorInvoker invoker = (NoiseBasedChunkGeneratorInvoker) (Object) self;
            try (NoiseChunk noiseChunk = invoker.ohmyworld$createNoiseChunk(
                    chunk, structureManager, blender, randomState, noiseSettings)) {
                invoker.ohmyworld$generateCarvers(chunk, blender, noiseChunk, randomState,
                        biomeManager, region, settings.materialRule().value());
            }
        } catch (Exception e) {
            if (CARVERS_ERROR_LOGGED.compareAndSet(false, true)) {
                LOGGER.error("ohmyworld: 26.3 carvers failed on formula terrain: {}", e.toString());
            }
        }
    }

    /**
     * H1（26.3）：材料通道（buildSurface）之后、雕刻之前。
     *
     * <p>26.3 的材料系统会在 buildSurface 阶段重写大片分节（含深层深板岩/石），
     * 因此后处理必须放在它之后才能保留（实测：放在 buildSurface 之前的写入
     * 会在材料通道中被整段覆盖）。
     */
    @Inject(method = "generateCarvers", at = @At("HEAD"))
    private void ohmyworld$beforeGenerateCarvers(ChunkAccess chunk, Blender blender, NoiseChunk noiseChunk,
                                                 RandomState randomState, BiomeManager biomeManager,
                                                 WorldGenRegion carverBiomeRegion,
                                                 net.minecraft.world.level.levelgen.material.rule.MaterialRule materialRule,
                                                 CallbackInfo ci) {
        ohmyworld$overlayAfterTerrain(chunk);
        // [carvers:vanilla-ew]：在雕刻之前激活“跳过水”保护（叠加模式与公式接管模式共用本入口）。
        PatternData.PatternSnapshot snapshot = PatternData.snapshotFor((ChunkGenerator) (Object) this);
        if (snapshot != null && snapshot.carversEw()) {
            CarverWaterGuard.begin(chunk);
        }
    }

    /** generateCarvers 返回：结束“跳过水”保护（仅当本生成器处于 vanilla-ew 模式）。 */
    @Inject(method = "generateCarvers", at = @At("RETURN"))
    private void ohmyworld$afterGenerateCarvers(ChunkAccess chunk, Blender blender, NoiseChunk noiseChunk,
                                                RandomState randomState, BiomeManager biomeManager,
                                                WorldGenRegion carverBiomeRegion,
                                                net.minecraft.world.level.levelgen.material.rule.MaterialRule materialRule,
                                                CallbackInfo ci) {
        PatternData.PatternSnapshot snapshot = PatternData.snapshotFor((ChunkGenerator) (Object) this);
        if (snapshot != null && snapshot.carversEw()) {
            CarverWaterGuard.end();
        }
    }

    /** [surface:none]：不跑原版的材料/表面通道（公式 surface 行随后补铺）。 */
    @Inject(method = "buildSurface", at = @At("HEAD"), cancellable = true)
    private void ohmyworld$onBuildSurface26(ChunkAccess chunk, NoiseChunk noiseChunk, RandomState randomState,
                                            BiomeManager biomeManager, Set<Holder<Biome>> possibleBiomes,
                                            net.minecraft.world.level.levelgen.material.rule.MaterialRule materialRule,
                                            CallbackInfo ci) {
        PatternData.PatternSnapshot snapshot = PatternData.snapshotFor((ChunkGenerator) (Object) this);
        if (snapshot != null && snapshot.overlay()
                && snapshot.surfaceMode() == DimensionRules.SurfaceMode.NONE) {
            ci.cancel();
        }
    }
    //?} else {
    @Inject(method = "fillFromNoise", at = @At("HEAD"), cancellable = true)
    private void ohmyworld$onFillFromNoise(Blender blender, RandomState randomState,
                                           StructureManager structureManager, ChunkAccess chunk,
                                           CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        ohmyworld$fillFromPattern(chunk, cir);
    }

    /**
     * 公式接管地形时（flat 公式模式），不再让原版表面规则改写公式方块。
     * 叠加模式按 [surface:...] 处理：[surface:none] 取消原版并在原地跑 H1（表面由公式铺）；
     * 其余模式放行原版（H1 在 RETURN 执行，统一为"原版表面之后"）。
     */
    @Inject(method = "buildSurface", at = @At("HEAD"), cancellable = true)
    private void ohmyworld$onBuildSurface(WorldGenRegion region, StructureManager structureManager,
                                          RandomState randomState, ChunkAccess chunk, CallbackInfo ci) {
        PatternData.PatternSnapshot snapshot = PatternData.snapshotFor((ChunkGenerator) (Object) this);
        if (snapshot == null) return;
        if (!snapshot.overlay()) {
            ci.cancel();
            return;
        }
        if (snapshot.surfaceMode() == DimensionRules.SurfaceMode.NONE) {
            // 不跑原版表面：H1（含补铺）就地执行后取消原版（RETURN 里不再执行）
            ohmyworld$overlayAfterTerrain(chunk);
            ci.cancel();
        }
    }

    /** H1（1.21.x）：原版表面之后（与 26.3 一致）、雕刻之前。 */
    @Inject(method = "buildSurface", at = @At("RETURN"))
    private void ohmyworld$afterBuildSurface(WorldGenRegion region, StructureManager structureManager,
                                             RandomState randomState, ChunkAccess chunk, CallbackInfo ci) {
        PatternData.PatternSnapshot snapshot = PatternData.snapshotFor((ChunkGenerator) (Object) this);
        if (snapshot == null || !snapshot.overlay()) return;
        if (snapshot.surfaceMode() == DimensionRules.SurfaceMode.NONE) return; // 已在 HEAD 执行
        ohmyworld$overlayAfterTerrain(chunk);
    }

    /** 公式接管地形时，不再让原版雕刻器在公式方块上挖洞；[carvers:vanilla]（含 vanilla-ew）可放行。 */
    //? >=1.21.2 {
    @Inject(method = "applyCarvers", at = @At("HEAD"), cancellable = true)
    private void ohmyworld$onApplyCarvers(WorldGenRegion region, long seed, RandomState randomState,
                                          BiomeManager biomeManager, StructureManager structureManager,
                                          ChunkAccess chunk, CallbackInfo ci) {
        ohmyworld$carversGate(chunk, ci);
    }

    @Inject(method = "applyCarvers", at = @At("RETURN"))
    private void ohmyworld$afterApplyCarvers(WorldGenRegion region, long seed, RandomState randomState,
                                             BiomeManager biomeManager, StructureManager structureManager,
                                             ChunkAccess chunk, CallbackInfo ci) {
        CarverWaterGuard.end();
    }
    //?} else {
    @Inject(method = "applyCarvers", at = @At("HEAD"), cancellable = true)
    private void ohmyworld$onApplyCarvers(WorldGenRegion region, long seed, RandomState randomState,
                                          BiomeManager biomeManager, StructureManager structureManager,
                                          ChunkAccess chunk, GenerationStep.Carving step, CallbackInfo ci) {
        if (step != GenerationStep.Carving.AIR) return;
        ohmyworld$carversGate(chunk, ci);
    }

    @Inject(method = "applyCarvers", at = @At("RETURN"))
    private void ohmyworld$afterApplyCarvers(WorldGenRegion region, long seed, RandomState randomState,
                                             BiomeManager biomeManager, StructureManager structureManager,
                                             ChunkAccess chunk, GenerationStep.Carving step, CallbackInfo ci) {
        CarverWaterGuard.end();
    }
    //?}

    /** 雕刻门控：未接管 / 不该雕刻 → 取消；vanilla-ew → 激活“跳过水”保护。 */
    private void ohmyworld$carversGate(ChunkAccess chunk, CallbackInfo ci) {
        PatternData.PatternSnapshot snapshot = PatternData.snapshotFor((ChunkGenerator) (Object) this);
        if (OhMyWorldConfig.debugLogsEnabled() && CARVERS_LOGGED.compareAndSet(false, true)) {
            LOGGER.info("ohmyworld: applyCarvers seen (formula bound={}, carversVanilla={})",
                    snapshot != null, snapshot != null && snapshot.carversVanilla());
        }
        if (snapshot == null) {
            // 非本模组生成器（含 FlatCarvers 的临时代理）：不干预已有保护状态
            return;
        }
        if (!snapshot.carversVanilla()) {
            ci.cancel();
            return;
        }
        if (snapshot.carversEw()) CarverWaterGuard.begin(chunk);
    }
    //?}

    @Inject(method = "getBaseHeight", at = @At("HEAD"), cancellable = true)
    private void ohmyworld$onGetBaseHeight(int x, int z, Heightmap.Types type,
                                           LevelHeightAccessor level, RandomState random,
                                           CallbackInfoReturnable<Integer> cir) {
        PatternData.PatternSnapshot snapshot = PatternData.snapshotFor((ChunkGenerator) (Object) this);
        if (snapshot == null) return;
        if (snapshot.layers().isEmpty()) return;
        // 叠加模式（R-D）：结构放置用原版高度，不反映叠加地形
        if (snapshot.overlay()) return;

        try {
            cir.setReturnValue(PatternData.getBaseHeight(snapshot, x, z, type,
                    LevelHeights.minY(level), LevelHeights.maxY(level),
                    PatternData.biomeViewFor(level)));
        } catch (Exception e) {
            LOGGER.error("ohmyworld: formula base-height evaluation failed, disabling pattern", e);
            PatternData.clearActive();
        }
    }

    @Inject(method = "getBaseColumn", at = @At("HEAD"), cancellable = true)
    private void ohmyworld$onGetBaseColumn(int x, int z, LevelHeightAccessor height, RandomState random,
                                           CallbackInfoReturnable<NoiseColumn> cir) {
        PatternData.PatternSnapshot snapshot = PatternData.snapshotFor((ChunkGenerator) (Object) this);
        if (snapshot == null) return;
        if (snapshot.layers().isEmpty()) return;
        // 叠加模式（R-D）：结构放置用原版列，不反映叠加地形
        if (snapshot.overlay()) return;

        int minY = LevelHeights.minY(height);
        int total = height.getHeight();
        try {
            BlockState[] column = PatternData.buildColumn(snapshot, x, z, minY, total,
                    PatternData.biomeViewFor(height));
            cir.setReturnValue(new NoiseColumn(minY, column));
        } catch (Exception e) {
            LOGGER.error("ohmyworld: formula base-column evaluation failed, disabling pattern", e);
            PatternData.clearActive();
        }
    }

    /** 两代地形构建入口共用的公式填充主体（返回是否真的接管了地形；与 {@code MixinFlatLevelSource} 同构）。 */
    private boolean ohmyworld$fillFromPattern(ChunkAccess chunk, CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        PatternData.PatternSnapshot snapshot = PatternData.snapshotFor((ChunkGenerator) (Object) this);
        if (snapshot == null || snapshot.layers().isEmpty()) return false;
        // 叠加模式：不接管地形，让原版先跑，随后在 H1 做后处理（见 ohmyworld$overlayAfterTerrain）
        if (snapshot.overlay()) return false;

        try {
            PatternData.fillChunk(chunk, snapshot, PatternData.vanillaViewFor((ChunkGenerator) (Object) this));
        } catch (Exception e) {
            // 兜底：任何公式求值/填充异常都不应破坏区块生成，
            // 回退到原版生成并停用图案，避免反复报错。
            LOGGER.error("ohmyworld: formula chunk fill failed, disabling pattern", e);
            PatternData.clearActive();
            PatternData.clearPending();
            return false;
        }
        cir.setReturnValue(CompletableFuture.completedFuture(chunk));
        return true;
    }

    /** 叠加模式（H1）：原版地形写完后的公式后处理；非 overlay 时无事发生。 */
    private void ohmyworld$overlayAfterTerrain(ChunkAccess chunk) {
        PatternData.PatternSnapshot snapshot = PatternData.snapshotFor((ChunkGenerator) (Object) this);
        if (snapshot == null || !snapshot.overlay()) return;
        if (OhMyWorldConfig.debugLogsEnabled() && OVERLAY_LOGGED.compareAndSet(false, true)) {
            LOGGER.info("ohmyworld: overlay terrain processing first chunk {}", chunk.getPos());
        }
        try {
            PatternData.overlayChunk(chunk, snapshot,
                    PatternData.vanillaViewFor((ChunkGenerator) (Object) this));
        } catch (Exception e) {
            LOGGER.error("ohmyworld: overlay terrain processing failed, disabling pattern", e);
            PatternData.clearActive();
            PatternData.clearPending();
        }
    }

    /** 调试日志：叠加后处理是否触发，只记录一次。 */
    private static final java.util.concurrent.atomic.AtomicBoolean OVERLAY_LOGGED =
            new java.util.concurrent.atomic.AtomicBoolean();
}
