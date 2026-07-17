package com.kncatl.ohmyworld.mixin;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;

import com.kncatl.ohmyworld.CyclicLayerDef;
import com.kncatl.ohmyworld.FormulaLayerDef;
import com.kncatl.ohmyworld.PatternData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(FlatLevelSource.class)
public class MixinFlatLevelSource {

    @Inject(method = "fillFromNoise", at = @At("HEAD"), cancellable = true)
    private void ohmyworld$onFillFromNoise(Blender blender, RandomState randomState,
                                            StructureManager structureManager,
                                            ChunkAccess chunk,
                                            CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        if (!PatternData.isActive()) return;
        List<Object> layers = PatternData.get();
        if (layers == null || layers.isEmpty()) return;

        PatternData.fillChunk(chunk, layers);
        cir.setReturnValue(CompletableFuture.completedFuture(chunk));
    }

    @Inject(method = "getBaseHeight", at = @At("HEAD"), cancellable = true)
    private void ohmyworld$onGetBaseHeight(int x, int z, Heightmap.Types type,
                                            LevelHeightAccessor level, RandomState random,
                                            CallbackInfoReturnable<Integer> cir) {
        if (!PatternData.isActive()) return;
        List<Object> layers = PatternData.get();
        if (layers == null || layers.isEmpty()) return;

        int top = level.getMinBuildHeight();
        for (Object obj : layers) {
            int yEnd;
            if (obj instanceof FormulaLayerDef f) yEnd = f.yEnd();
            else if (obj instanceof CyclicLayerDef c) yEnd = c.yEnd();
            else continue;
            if (yEnd >= top) top = yEnd + 1;
        }
        cir.setReturnValue(top);
    }

    @Inject(method = "getBaseColumn", at = @At("HEAD"), cancellable = true)
    private void ohmyworld$onGetBaseColumn(int x, int z, LevelHeightAccessor height, RandomState random,
                                            CallbackInfoReturnable<NoiseColumn> cir) {
        if (!PatternData.isActive()) return;
        List<Object> layers = PatternData.get();
        if (layers == null || layers.isEmpty()) return;

        int minY = height.getMinBuildHeight();
        int total = height.getHeight();
        BlockState[] column = new BlockState[total];
        Arrays.fill(column, Blocks.AIR.defaultBlockState());

        for (Object obj : layers) {
            if (obj instanceof FormulaLayerDef f) {
                for (int y = f.yStart(); y <= f.yEnd(); y++) {
                    int idx = y - minY;
                    if (idx >= 0 && idx < total) column[idx] = f.getBlock(x, z, y);
                }
            } else if (obj instanceof CyclicLayerDef c) {
                for (int y = c.yStart(); y <= c.yEnd(); y++) {
                    int idx = y - minY;
                    if (idx >= 0 && idx < total) column[idx] = c.getBlock(x, z, y);
                }
            }
        }
        cir.setReturnValue(new NoiseColumn(minY, column));
    }
}
