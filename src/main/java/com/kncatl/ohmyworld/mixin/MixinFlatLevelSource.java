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
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;

import com.kncatl.ohmyworld.FlatCarvers;
import com.kncatl.ohmyworld.PatternData;
import com.kncatl.ohmyworld.compat.LevelHeights;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(FlatLevelSource.class)
public class MixinFlatLevelSource {
    private static final Logger LOGGER = LogUtils.getLogger();

    //? >=26.3 {
    // 26.3 把地形构建重做成单入口 buildTerrain：(fillFromNoise + buildSurface +
    // applyCarvers) 合并为这一处，旧方法名在目标类里不复存在。
    @Inject(method = "buildTerrain", at = @At("HEAD"), cancellable = true)
    private void ohmyworld$onBuildTerrain(ChunkAccess chunk, Blender blender, RandomState randomState,
                                          StructureManager structureManager, BiomeManager biomeManager,
                                          WorldGenRegion region, Set<Holder<Biome>> biomes,
                                          CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        if (ohmyworld$fillFromPattern(chunk, cir)
                && PatternData.carversVanillaFor((ChunkGenerator) (Object) this)) {
            // 26.3 的雕刻并入本入口：公式填充完成后补跑原版雕刻（干燥代理，见 FlatCarvers）
            FlatCarvers.carveModern((ChunkGenerator) (Object) this, region, blender,
                    biomeManager, structureManager, chunk);
        }
    }
    //?} else {
    @Inject(method = "fillFromNoise", at = @At("HEAD"), cancellable = true)
    private void ohmyworld$onFillFromNoise(Blender blender, RandomState randomState,
                                            StructureManager structureManager,
                                            ChunkAccess chunk,
                                            CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        ohmyworld$fillFromPattern(chunk, cir);
    }
    //?}

    //? <26.3 {
    /**
     * 超平坦生成器的 applyCarvers 是空实现：公式接管 + {@code [carvers:vanilla]} 时，
     * 借一个"干燥"的临时噪声生成器按原版逻辑雕刻（只掏空气、不灌水）。
     * 1.21.1 的方法多一个 {@code GenerationStep.Carving} 参数（管线按 AIR/LIQUID
     * 分步调用），只在 AIR 步执行；1.21.2+ 无该参数、每区块调用一次。
     */
    //? >=1.21.2 {
    @Inject(method = "applyCarvers", at = @At("HEAD"))
    private void ohmyworld$onApplyCarvers(WorldGenRegion region, long seed, RandomState randomState,
                                          BiomeManager biomeManager, StructureManager structureManager,
                                          ChunkAccess chunk, CallbackInfo ci) {
        ohmyworld$carveIfEnabled(region, seed, biomeManager, structureManager, chunk);
    }
    //?} else {
    @Inject(method = "applyCarvers", at = @At("HEAD"))
    private void ohmyworld$onApplyCarvers(WorldGenRegion region, long seed, RandomState randomState,
                                          BiomeManager biomeManager, StructureManager structureManager,
                                          ChunkAccess chunk, GenerationStep.Carving step, CallbackInfo ci) {
        if (step == GenerationStep.Carving.AIR) {
            ohmyworld$carveIfEnabled(region, seed, biomeManager, structureManager, chunk);
        }
    }
    //?}

    private void ohmyworld$carveIfEnabled(WorldGenRegion region, long seed, BiomeManager biomeManager,
                                          StructureManager structureManager, ChunkAccess chunk) {
        if (!PatternData.carversVanillaFor((ChunkGenerator) (Object) this)) return;
        FlatCarvers.carve((ChunkGenerator) (Object) this, region, seed, biomeManager, structureManager, chunk);
    }
    //?}

    /**
     * 两代地形构建入口共用的公式填充主体（返回是否真的接管了地形）。
     *
     * <p>26.3 起入口是 {@code buildTerrain}，更早是 {@code fillFromNoise}；两者
     * 语义相同（写入地形方块并返回区块），参数只是版本差异，不参与计算。
     */
    private boolean ohmyworld$fillFromPattern(ChunkAccess chunk, CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        PatternData.PatternSnapshot snapshot = PatternData.snapshotFor((FlatLevelSource) (Object) this);
        if (snapshot == null || snapshot.layers().isEmpty()) return false;

        try {
            PatternData.fillChunk(chunk, snapshot);
        } catch (Exception e) {
            // 兜底：任何公式求值/填充异常都不应破坏区块生成，
            // 回退到原版平坦生成并停用图案，避免反复报错。
            LOGGER.error("ohmyworld: formula chunk fill failed, disabling pattern", e);
            PatternData.clearActive();
            PatternData.clearPending();
            return false;
        }
        cir.setReturnValue(CompletableFuture.completedFuture(chunk));
        return true;
    }

    @Inject(method = "getBaseHeight", at = @At("HEAD"), cancellable = true)
    private void ohmyworld$onGetBaseHeight(int x, int z, Heightmap.Types type,
                                            LevelHeightAccessor level, RandomState random,
                                            CallbackInfoReturnable<Integer> cir) {
        PatternData.PatternSnapshot snapshot = PatternData.snapshotFor((FlatLevelSource) (Object) this);
        if (snapshot == null) return;
        if (snapshot.layers().isEmpty()) return;

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
        PatternData.PatternSnapshot snapshot = PatternData.snapshotFor((FlatLevelSource) (Object) this);
        if (snapshot == null) return;
        if (snapshot.layers().isEmpty()) return;

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
}
