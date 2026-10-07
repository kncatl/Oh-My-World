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
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;

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
 * <p>版本分支说明：1.21.1 的 {@code applyCarvers} 比 1.21.2+ 多一个
 * {@code GenerationStep.Carving} 参数，因此该注入使用只声明 {@code CallbackInfo}
 * 的处理器（Mixin 允许省略目标方法的参数），与签名差异无关。
 */
@Mixin(NoiseBasedChunkGenerator.class)
public class MixinNoiseBasedChunkGenerator {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** 26.3 上 [carvers:vanilla] 不受支持，只提示一次。 */
    private static final java.util.concurrent.atomic.AtomicBoolean CARVERS_WARNED =
            new java.util.concurrent.atomic.AtomicBoolean();

    //? >=26.3 {
    // 26.3 把地形构建重做成单入口 buildTerrain：(fillFromNoise + buildSurface +
    // applyCarvers) 合并为这一处。
    @Inject(method = "buildTerrain", at = @At("HEAD"), cancellable = true)
    private void ohmyworld$onBuildTerrain(ChunkAccess chunk, Blender blender, RandomState randomState,
                                          StructureManager structureManager, BiomeManager biomeManager,
                                          WorldGenRegion region, Set<Holder<Biome>> biomes,
                                          CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        ohmyworld$fillFromPattern(chunk, cir);
    }
    //?} else {
    @Inject(method = "fillFromNoise", at = @At("HEAD"), cancellable = true)
    private void ohmyworld$onFillFromNoise(Blender blender, RandomState randomState,
                                           StructureManager structureManager, ChunkAccess chunk,
                                           CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        ohmyworld$fillFromPattern(chunk, cir);
    }

    /** 公式接管地形时，不再让原版表面规则改写公式方块。 */
    @Inject(method = "buildSurface", at = @At("HEAD"), cancellable = true)
    private void ohmyworld$onBuildSurface(WorldGenRegion region, StructureManager structureManager,
                                          RandomState randomState, ChunkAccess chunk, CallbackInfo ci) {
        if (PatternData.snapshotFor((ChunkGenerator) (Object) this) != null) ci.cancel();
    }

    /** 公式接管地形时，不再让原版雕刻器在公式方块上挖洞；[carvers:vanilla] 可放行。 */
    @Inject(method = "applyCarvers", at = @At("HEAD"), cancellable = true)
    private void ohmyworld$onApplyCarvers(CallbackInfo ci) {
        PatternData.PatternSnapshot snapshot = PatternData.snapshotFor((ChunkGenerator) (Object) this);
        if (snapshot != null && !snapshot.carversVanilla()) ci.cancel();
    }
    //?}

    @Inject(method = "getBaseHeight", at = @At("HEAD"), cancellable = true)
    private void ohmyworld$onGetBaseHeight(int x, int z, Heightmap.Types type,
                                           LevelHeightAccessor level, RandomState random,
                                           CallbackInfoReturnable<Integer> cir) {
        PatternData.PatternSnapshot snapshot = PatternData.snapshotFor((ChunkGenerator) (Object) this);
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
        PatternData.PatternSnapshot snapshot = PatternData.snapshotFor((ChunkGenerator) (Object) this);
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

    /** 两代地形构建入口共用的公式填充主体（与 {@code MixinFlatLevelSource} 同构）。 */
    private void ohmyworld$fillFromPattern(ChunkAccess chunk, CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        PatternData.PatternSnapshot snapshot = PatternData.snapshotFor((ChunkGenerator) (Object) this);
        if (snapshot == null || snapshot.layers().isEmpty()) return;
        List<Object> layers = snapshot.layers();

        //? >=26.3 {
        if (snapshot.carversVanilla() && CARVERS_WARNED.compareAndSet(false, true)) {
            LOGGER.warn("ohmyworld: [carvers:vanilla] is not supported on this version (carving is merged "
                    + "into buildTerrain and cannot run separately); carvers stay disabled");
        }
        //?}

        try {
            PatternData.fillChunk(chunk, layers);
        } catch (Exception e) {
            // 兜底：任何公式求值/填充异常都不应破坏区块生成，
            // 回退到原版生成并停用图案，避免反复报错。
            LOGGER.error("ohmyworld: formula chunk fill failed, disabling pattern", e);
            PatternData.clearActive();
            PatternData.clearPending();
            return;
        }
        cir.setReturnValue(CompletableFuture.completedFuture(chunk));
    }
}
