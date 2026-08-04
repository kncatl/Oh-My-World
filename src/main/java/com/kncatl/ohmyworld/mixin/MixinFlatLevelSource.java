package com.kncatl.ohmyworld.mixin;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;

import com.kncatl.ohmyworld.PatternData;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(FlatLevelSource.class)
public class MixinFlatLevelSource {
    private static final Logger LOGGER = LogUtils.getLogger();

    @Inject(method = "fillFromNoise", at = @At("HEAD"), cancellable = true)
    private void ohmyworld$onFillFromNoise(Blender blender, RandomState randomState,
                                            StructureManager structureManager,
                                            ChunkAccess chunk,
                                            CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        PatternData.PatternSnapshot snapshot = PatternData.snapshotFor((FlatLevelSource) (Object) this);
        if (snapshot == null || snapshot.layers().isEmpty()) return;
        List<Object> layers = snapshot.layers();

        try {
            PatternData.fillChunk(chunk, layers);
        } catch (Exception e) {
            // 兜底：任何公式求值/填充异常都不应破坏区块生成，
            // 回退到原版平坦生成并停用图案，避免反复报错。
            LOGGER.error("ohmyworld: formula chunk fill failed, disabling pattern", e);
            PatternData.clearActive();
            PatternData.clearPending();
            return;
        }
        cir.setReturnValue(CompletableFuture.completedFuture(chunk));
    }

    @Inject(method = "getBaseHeight", at = @At("HEAD"), cancellable = true)
    private void ohmyworld$onGetBaseHeight(int x, int z, Heightmap.Types type,
                                            LevelHeightAccessor level, RandomState random,
                                            CallbackInfoReturnable<Integer> cir) {
        PatternData.PatternSnapshot snapshot = PatternData.snapshotFor((FlatLevelSource) (Object) this);
        if (snapshot == null) return;
        if (snapshot.layers().isEmpty()) return;

        //? >=1.21.5 {
        int minY = level.getMinY();
        int maxY = level.getMaxY();
        //?} else {
        int minY = level.getMinBuildHeight();
        int maxY = level.getMaxBuildHeight();
        //?}
        try {
            cir.setReturnValue(PatternData.getBaseHeight(snapshot, x, z, type, minY, maxY));
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

        //? >=1.21.5 {
        int minY = height.getMinY();
        //?} else {
        int minY = height.getMinBuildHeight();
        //?}
        int total = height.getHeight();
        try {
            BlockState[] column = PatternData.buildColumn(snapshot, x, z, minY, total);
            cir.setReturnValue(new NoiseColumn(minY, column));
        } catch (Exception e) {
            LOGGER.error("ohmyworld: formula base-column evaluation failed, disabling pattern", e);
            PatternData.clearActive();
        }
    }
}
